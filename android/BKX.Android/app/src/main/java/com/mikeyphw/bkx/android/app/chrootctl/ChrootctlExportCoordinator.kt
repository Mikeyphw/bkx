package com.mikeyphw.bkx.android.app.chrootctl

import com.mikeyphw.bkx.android.app.runtime.BkxOperationCoordinator
import com.mikeyphw.bkx.android.core.bridge.BridgeResultStatus
import com.mikeyphw.bkx.android.core.chrootctl.ChrootctlBridgeCodec
import com.mikeyphw.bkx.android.core.chrootctl.ChrootctlBridgePort
import com.mikeyphw.bkx.android.core.chrootctl.ChrootctlExportPhase
import com.mikeyphw.bkx.android.core.chrootctl.ChrootctlExportPlan
import com.mikeyphw.bkx.android.core.chrootctl.ChrootctlExportPreview
import com.mikeyphw.bkx.android.core.chrootctl.ChrootctlExportProgress
import com.mikeyphw.bkx.android.core.chrootctl.ChrootctlExportPublication
import com.mikeyphw.bkx.android.core.chrootctl.ChrootctlOperation
import com.mikeyphw.bkx.android.core.chrootctl.ChrootctlOperationEvent
import com.mikeyphw.bkx.android.core.chrootctl.ChrootctlStagedArtifact
import com.mikeyphw.bkx.android.core.chrootctl.exportArtifactOrNull
import com.mikeyphw.bkx.android.core.chrootctl.reconciledExportArtifactOrNull
import com.mikeyphw.bkx.android.core.chrootctl.toChrootctlExportPreview
import com.mikeyphw.bkx.android.core.transfer.TransferEndpoint
import com.mikeyphw.bkx.android.core.transfer.TransferMode
import com.mikeyphw.bkx.android.core.transfer.TransferRequestBuilder
import com.mikeyphw.bkx.android.core.transfer.TransferTopologyDraft
import com.mikeyphw.bkx.android.core.transfer.toTransferPreviewSummary
import com.mikeyphw.bkx.android.root.ChrootctlArtifactHandoff
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

class ChrootctlExportPublicationException(
    message: String,
    val stagedArtifact: ChrootctlStagedArtifact? = null,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * CCT-03 export pipeline.
 *
 * chrootctl owns chroot archive creation and its root-managed artifact. BKX
 * copies that exact registered artifact into app-owned staging, verifies the
 * chrootctl SHA-256, and then publishes it through BKX's existing transfer
 * plan/run contract. BKX credentials are therefore never copied into
 * chrootctl's root-owned rclone configuration.
 */
class ChrootctlExportCoordinator(
    private val chrootctl: ChrootctlBridgePort,
    private val handoff: ChrootctlArtifactHandoff,
    private val bkxOperations: BkxOperationCoordinator,
    private val transferRequests: TransferRequestBuilder = TransferRequestBuilder(),
    private val requestIdFactory: (String) -> String = ::newRequestId,
) {
    suspend fun preview(plan: ChrootctlExportPlan): ChrootctlExportPreview {
        val requestId = requestIdFactory("chroot-export-preview")
        val response = chrootctl.preview(
            ChrootctlBridgeCodec.request(
                requestId = requestId,
                operation = ChrootctlOperation.PreviewExport,
                payload = ChrootctlBridgeCodec.exportPayload(plan.chrootName),
            ),
        )
        return response.toChrootctlExportPreview(
            plan = plan,
            bkxStagingFreeBytes = handoff.stagingFreeBytes(),
            bkxStagingReserveBytes = BKX_STAGING_RESERVE_BYTES,
        )
    }

    suspend fun exportAndPublish(
        plan: ChrootctlExportPlan,
        preview: ChrootctlExportPreview,
        password: String? = null,
        onProgress: suspend (ChrootctlExportProgress) -> Unit = {},
    ): ChrootctlExportPublication {
        require(preview.planFingerprint == plan.fingerprint()) {
            "The chroot export preview no longer matches the selected chroot or destination."
        }
        require(preview.isExecutable) {
            preview.blockers.joinToString(prefix = "Chroot export is blocked: ") { it.message }
        }
        onProgress(ChrootctlExportProgress(ChrootctlExportPhase.Exporting, 0, "Starting native chrootctl export."))
        val operationRequestId = requestIdFactory("chroot-export")
        val effectivePassword = password?.takeIf { it.isNotEmpty() }
        val exportPayload = ChrootctlBridgeCodec.bindPreview(
            ChrootctlBridgeCodec.exportPayload(plan.chrootName, password = effectivePassword),
            preview.contract,
        )
        val run = chrootctl.run(
            ChrootctlBridgeCodec.request(operationRequestId, ChrootctlOperation.Export, exportPayload),
        ) { event ->
            progressFrom(event)?.let { onProgress(it) }
        }
        if (!run.start.ok) {
            throw ChrootctlExportPublicationException(run.start.error?.message ?: "chrootctl refused to start the export.")
        }
        val exportArtifact = run.terminalEvent?.exportArtifactOrNull()
            ?: run.operationId?.let { operationId ->
                chrootctl.reconcile(operationId).reconciledExportArtifactOrNull()
            }
            ?: throw ChrootctlExportPublicationException(
                run.terminalEvent?.error?.message
                    ?: "chrootctl export ended without a trustworthy successful result. The operation can be reviewed by its persistent operation id.",
            )
        if (exportArtifact.chrootName != plan.chrootName) {
            throw ChrootctlExportPublicationException("chrootctl export result does not match the requested chroot.")
        }
        val expectedManagedArchive = if (effectivePassword != null && !preview.managedArchivePath.endsWith(".enc")) {
            preview.managedArchivePath + ".enc"
        } else {
            preview.managedArchivePath
        }
        if (exportArtifact.archivePath != expectedManagedArchive || exportArtifact.encrypted != (effectivePassword != null)) {
            throw ChrootctlExportPublicationException(
                "chrootctl export result does not match the managed artifact approved by preview.",
            )
        }

        onProgress(ChrootctlExportProgress(ChrootctlExportPhase.Handoff, 0, "Copying the verified chrootctl artifact into BKX staging."))
        val staged = handoff.stage(
            requestId = operationRequestId,
            artifact = exportArtifact,
        ) { copied ->
            // The compressed artifact size is authoritative only after chrootctl
            // publishes it. Do not derive a misleading percentage from the
            // uncompressed source-size estimate returned by export preview.
            onProgress(
                ChrootctlExportProgress(
                    ChrootctlExportPhase.Handoff,
                    null,
                    "Copied ${copied.coerceAtLeast(0L)} bytes into BKX staging.",
                ),
            )
        }

        try {
            onProgress(ChrootctlExportProgress(ChrootctlExportPhase.TransferPreview, null, "Reviewing the BKX publication topology."))
            val transferDraft = transferDraft(plan, staged)
            val transferPlanRequestId = requestIdFactory("chroot-publish-plan")
            val planOutcome = bkxOperations.execute(
                request = transferRequests.buildPreviewRequest(transferPlanRequestId, transferDraft),
                recordInHistory = false,
            )
            val planResult = (planOutcome as? BkxOperationCoordinator.OperationOutcome.Completed)?.result
                ?: throw ChrootctlExportPublicationException("BKX could not create a publication plan for the staged chroot export.", staged)
            if (planResult.status != BridgeResultStatus.Success) {
                throw ChrootctlExportPublicationException(planResult.summary.ifBlank { "BKX publication plan failed." }, staged)
            }
            val transferPreview = planResult.toTransferPreviewSummary(transferDraft)
            require(transferPreview.isExecutable) {
                "BKX publication plan is blocked by a destination collision or missing plan proof."
            }

            onProgress(ChrootctlExportProgress(ChrootctlExportPhase.Publishing, 0, "Publishing the chroot export to the selected BKX destination."))
            val runOutcome = bkxOperations.execute(
                request = transferRequests.buildRunRequest(requestIdFactory("chroot-publish"), transferDraft, transferPreview),
                recordInHistory = true,
            )
            val transferResult = (runOutcome as? BkxOperationCoordinator.OperationOutcome.Completed)?.result
                ?: throw ChrootctlExportPublicationException("BKX publication did not produce a final result.", staged)
            if (transferResult.status != BridgeResultStatus.Success) {
                throw ChrootctlExportPublicationException(
                    transferResult.summary.ifBlank { "BKX publication failed; the verified staged export was retained for recovery." },
                    staged,
                )
            }
            val stagingCleaned = handoff.cleanup(staged)
            onProgress(
                ChrootctlExportProgress(
                    ChrootctlExportPhase.Complete,
                    100,
                    if (stagingCleaned) {
                        "Chroot export published and BKX staging cleaned."
                    } else {
                        "Chroot export published; the verified BKX staging copy was retained because cleanup did not complete."
                    },
                ),
            )
            return ChrootctlExportPublication(
                chrootName = plan.chrootName,
                destinationLocation = plan.destination.stagedDirectoryPath,
                checksumSha256 = staged.checksumSha256,
                encrypted = exportArtifact.encrypted,
                retainedChrootctlArtifactPath = exportArtifact.archivePath,
                retainedBkxStagingPath = staged.stagedPath.takeUnless { stagingCleaned },
            )
        } catch (error: CancellationException) {
            // Publication may not have completed. Keep the verified staged copy
            // for explicit recovery rather than deleting it during cancellation.
            throw error
        } catch (error: ChrootctlExportPublicationException) {
            throw error
        } catch (error: Throwable) {
            throw ChrootctlExportPublicationException(
                "BKX could not publish the verified staged chroot export: ${error.message ?: error.javaClass.simpleName}. The staged artifact was retained for recovery.",
                staged,
                error,
            )
        }
    }

    private fun transferDraft(plan: ChrootctlExportPlan, staged: ChrootctlStagedArtifact): TransferTopologyDraft = TransferTopologyDraft(
        mode = TransferMode.Push,
        sources = listOf(
            TransferEndpoint(
                endpointId = "chrootctl-staged-${staged.checksumSha256.take(16)}",
                label = staged.fileName,
                location = staged.stagedPath,
            ),
        ),
        destination = TransferEndpoint(
            endpointId = plan.destination.destinationId,
            label = plan.destination.label,
            location = plan.destination.stagedDirectoryPath,
        ),
        verifyAfterTransfer = true,
        checksum = staged.checksumSha256,
        overwriteExisting = plan.overwriteExisting,
    )

    private fun progressFrom(event: ChrootctlOperationEvent): ChrootctlExportProgress? {
        if (event.type != "operation_progress") return null
        val percent = (event.payload["percent"] as? JsonPrimitive)?.intOrNull
        val message = (event.payload["message"] as? JsonPrimitive)?.contentOrNull
            ?: (event.payload["phase"] as? JsonPrimitive)?.contentOrNull
            ?: "chrootctl export is running."
        return ChrootctlExportProgress(ChrootctlExportPhase.Exporting, percent?.coerceIn(0, 100), message)
    }

    private companion object {
        const val BKX_STAGING_RESERVE_BYTES = 64L * 1024L * 1024L

        fun newRequestId(prefix: String): String = "$prefix-${UUID.randomUUID().toString().replace("-", "").take(24)}"
    }
}
