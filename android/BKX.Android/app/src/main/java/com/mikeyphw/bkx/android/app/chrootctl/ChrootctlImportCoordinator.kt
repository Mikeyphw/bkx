package com.mikeyphw.bkx.android.app.chrootctl

import com.mikeyphw.bkx.android.core.chrootctl.*
import com.mikeyphw.bkx.android.root.ChrootctlImportHandoff
import com.mikeyphw.bkx.android.app.runtime.BkxOperationCoordinator
import com.mikeyphw.bkx.android.core.bridge.BridgeResultStatus
import com.mikeyphw.bkx.android.core.transfer.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Guarded native chroot import. Acquisition into BKX staging is performed by the existing transfer layer/UI. */
class ChrootctlImportCoordinator(
    private val chrootctl: ChrootctlBridgePort,
    private val handoff: ChrootctlImportHandoff,
    private val bkxOperations: BkxOperationCoordinator,
    private val transferRequests: TransferRequestBuilder = TransferRequestBuilder(),
    private val requestIdFactory: (String) -> String = { p -> "$p-${UUID.randomUUID().toString().replace("-", "").take(24)}" },
) {

    suspend fun acquire(plan: ChrootctlImportPlan, onProgress: suspend (ChrootctlImportProgress) -> Unit = {}): File {
        onProgress(ChrootctlImportProgress(ChrootctlImportPhase.Acquiring, null, "Acquiring the chroot archive into BKX staging."))
        val root=handoff.acquisitionRoot(); if(!root.exists() && !root.mkdirs()) error("Unable to create BKX chroot import staging.")
        val name=plan.sourceLocation.substringAfterLast('/').substringAfterLast(':').ifBlank { "chroot-import.tar.zst" }
        require('/' !in name && '\\' !in name && name !in setOf(".","..")) { "Import source has an invalid filename." }
        val destinationFile=File(root, name)
        if(destinationFile.exists()) require(destinationFile.delete()) { "Unable to replace stale BKX import staging file." }
        val remote=plan.sourceLocation.startsWith("rclone://", true)
        if(!remote) {
            val source=File(plan.sourceLocation)
            require(source.isFile) { "Selected local chroot archive does not exist." }
            require(handoff.acquisitionFreeBytes() > source.length()) { "BKX chroot import staging does not have enough free space." }
            source.inputStream().use { input -> destinationFile.outputStream().use { output -> input.copyTo(output, 256*1024) } }
            return destinationFile
        }
        val draft=TransferTopologyDraft(
            mode=TransferMode.Pull,
            sources=listOf(TransferEndpoint("chroot-import-source", name, plan.sourceLocation)),
            destination=TransferEndpoint("chroot-import-staging", "BKX chroot import staging", root.absolutePath),
            verifyAfterTransfer=true,
            overwriteExisting=false,
        )
        val previewOutcome=bkxOperations.execute(transferRequests.buildPreviewRequest(requestIdFactory("chroot-import-acquire-plan"),draft), recordInHistory=false)
        val previewResult=(previewOutcome as? BkxOperationCoordinator.OperationOutcome.Completed)?.result ?: error("BKX could not plan chroot archive acquisition.")
        check(previewResult.status==BridgeResultStatus.Success) { previewResult.summary }
        val transferPreview=previewResult.toTransferPreviewSummary(draft)
        check(transferPreview.isExecutable) { "Chroot archive acquisition is blocked." }
        val run=bkxOperations.execute(transferRequests.buildRunRequest(requestIdFactory("chroot-import-acquire"),draft,transferPreview), recordInHistory=true)
        val result=(run as? BkxOperationCoordinator.OperationOutcome.Completed)?.result ?: error("BKX chroot archive acquisition did not complete.")
        check(result.status==BridgeResultStatus.Success) { result.summary }
        check(destinationFile.isFile && destinationFile.length()>0) { "BKX transfer completed without the expected acquired archive." }
        return destinationFile
    }

    suspend fun stageAndPreview(plan: ChrootctlImportPlan, acquiredArchive: File, password: String? = null): Pair<ChrootctlStagedImport, ChrootctlImportPreview> {
        val staged=handoff.stageIntoChrootctl(requestIdFactory("chroot-import-stage"), acquiredArchive)
        check(handoff.cleanupAcquisition(acquiredArchive)) { "Root handoff succeeded but BKX could not remove its duplicate acquisition staging file." }
        return try {
            val requestId=requestIdFactory("chroot-import-preview")
            val payload=ChrootctlBridgeCodec.importPayload(staged.archivePath, plan.requestedName, plan.conflict, plan.allowLegacyMetadata, plan.importStrategy, password)
            val response=chrootctl.preview(ChrootctlBridgeCodec.request(requestId, ChrootctlOperation.ImportPreview, payload))
            staged to response.toChrootctlImportPreview(plan, staged)
        } catch (error: Throwable) {
            withContext(NonCancellable) { runCatching { handoff.cleanupRootStaging(staged.stagingId, staged.checksumSha256) } }
            throw error
        }
    }

    suspend fun cleanupRetained(preview: ChrootctlImportPreview): Boolean =
        handoff.cleanupRootStaging(preview.stagingId, preview.stagedChecksumSha256)

    suspend fun import(
        plan: ChrootctlImportPlan,
        preview: ChrootctlImportPreview,
        password: String? = null,
        confirmationGranted: Boolean,
        onProgress: suspend (ChrootctlImportProgress) -> Unit = {},
    ): ChrootctlImportResult {
        require(confirmationGranted) { "Chroot import requires interactive confirmation." }
        require(preview.planFingerprint == plan.fingerprint()) { "Import choices changed after preview; review the archive again." }
        require(preview.isExecutable) { preview.blockers.joinToString(prefix="Chroot import is blocked: ") { it.message } }
        if(preview.inspection.metadataFidelity == "legacy_unverified") require(plan.allowLegacyMetadata) { "Legacy metadata compatibility must be explicitly enabled." }
        val requestId=requestIdFactory("chroot-import")
        val payload=ChrootctlBridgeCodec.bindPreview(
            ChrootctlBridgeCodec.importPayload(preview.stagedArchivePath, plan.requestedName, plan.conflict, plan.allowLegacyMetadata, plan.importStrategy, password, postImport="register"),
            preview.contract,
        )
        var persistentId: String?=null
        try {
            onProgress(ChrootctlImportProgress(ChrootctlImportPhase.Importing,0,"Importing through chrootctl."))
            val run=chrootctl.run(ChrootctlBridgeCodec.request(requestId, ChrootctlOperation.Import, payload)) { event ->
                if(event.operationId.isNotBlank()) persistentId=event.operationId
                if(event.type=="operation_progress") {
                    val pct=(event.payload["percent"] as? JsonPrimitive)?.intOrNull?.coerceIn(0,100)
                    val msg=(event.payload["message"] as? JsonPrimitive)?.contentOrNull ?: "chrootctl import is running."
                    onProgress(ChrootctlImportProgress(ChrootctlImportPhase.Importing,pct,msg))
                }
            }
            persistentId = run.operationId ?: persistentId
            if(!run.start.ok) {
                withContext(NonCancellable) { runCatching { handoff.cleanupRootStaging(preview.stagingId, preview.stagedChecksumSha256) } }
                error(run.start.error?.message ?: "chrootctl refused to start import.")
            }
            val direct=run.terminalEvent?.importResultOrNull()
            if(direct != null) {
                val cleaned=withContext(NonCancellable) { runCatching { handoff.cleanupRootStaging(preview.stagingId, preview.stagedChecksumSha256) }.getOrDefault(false) }
                return direct.copy(operationId=persistentId, retainedStagingId=preview.stagingId.takeUnless { cleaned })
            }
            val id=persistentId ?: error("Import stream ended without a persistent operation id.")
            val reconciled=chrootctl.reconcile(id)
            val record=reconciled.data as? kotlinx.serialization.json.JsonObject ?: error("Unable to reconcile chroot import.")
            val state=(record["state"] as? JsonPrimitive)?.contentOrNull
            val terminal = state.equals("succeeded",true) || state.equals("failed",true) || state.equals("cancelled",true) || state.equals("canceled",true)
            val cleaned = if (terminal) withContext(NonCancellable) { runCatching { handoff.cleanupRootStaging(preview.stagingId, preview.stagedChecksumSha256) }.getOrDefault(false) } else false
            check(state.equals("succeeded",true)) { "chrootctl import did not complete successfully (state=${state ?: "unknown"}); retained staging id=${if (cleaned) "none" else preview.stagingId}." }
            val result=(record["resultPayload"] ?: record["result_payload"]) as? kotlinx.serialization.json.JsonObject ?: error("Successful import is missing result data.")
            fun s(k:String)=(result[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
            return ChrootctlImportResult(s("name"),s("path"),s("metadata_fidelity"),id,preview.stagingId.takeUnless { cleaned })
        } catch(cancelled: CancellationException) {
            persistentId?.let { id -> withContext(NonCancellable) { runCatching { chrootctl.cancel(id) } } }
            throw cancelled
        }
    }
}
