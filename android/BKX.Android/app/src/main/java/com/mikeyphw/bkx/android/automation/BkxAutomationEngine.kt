package com.mikeyphw.bkx.android.automation

import com.mikeyphw.bkx.android.app.drive.GoogleDriveAuthorizationManager
import com.mikeyphw.bkx.android.app.drive.GoogleDriveAuthorizationOutcome
import com.mikeyphw.bkx.android.app.runtime.BkxOperationCoordinator
import com.mikeyphw.bkx.android.app.runtime.BkxOperationCoordinator.OperationOutcome
import com.mikeyphw.bkx.android.core.archives.ArchiveBrowsePageRequest
import com.mikeyphw.bkx.android.core.archives.ArchiveLocation
import com.mikeyphw.bkx.android.core.archives.ArchiveRequestBuilder
import com.mikeyphw.bkx.android.core.backup.BackupCompression
import com.mikeyphw.bkx.android.core.backup.BackupDestinationSelection
import com.mikeyphw.bkx.android.core.backup.BackupFilterRules
import com.mikeyphw.bkx.android.core.backup.BackupPlanDraft
import com.mikeyphw.bkx.android.core.backup.BackupPlanPreview
import com.mikeyphw.bkx.android.core.backup.BackupPlanWarning
import com.mikeyphw.bkx.android.core.backup.BackupRequestBuilder
import com.mikeyphw.bkx.android.core.backup.BackupSourceSelection
import com.mikeyphw.bkx.android.core.backup.BackupSplitPolicy
import com.mikeyphw.bkx.android.core.backup.BackupVerificationExpectation
import com.mikeyphw.bkx.android.core.backup.BackupVerificationPolicy
import com.mikeyphw.bkx.android.core.backup.BackupWarningSeverity
import com.mikeyphw.bkx.android.core.bridge.BridgeRequestEnvelope
import com.mikeyphw.bkx.android.core.bridge.BridgeResultEnvelope
import com.mikeyphw.bkx.android.core.bridge.BridgeResultStatus
import com.mikeyphw.bkx.android.core.drive.GoogleDriveRcloneConfig
import com.mikeyphw.bkx.android.core.operations.OperationRuntimeState
import com.mikeyphw.bkx.android.core.restore.RestoreDestinationPolicy
import com.mikeyphw.bkx.android.core.restore.RestoreDestinationSelection
import com.mikeyphw.bkx.android.core.restore.RestoreDraft
import com.mikeyphw.bkx.android.core.restore.RestoreExtractionConfirmation
import com.mikeyphw.bkx.android.core.restore.RestoreOutputMode
import com.mikeyphw.bkx.android.core.restore.RestoreRequestBuilder
import com.mikeyphw.bkx.android.core.restore.RestoreSubsetSelection
import com.mikeyphw.bkx.android.core.restore.toRestorePreviewSummary
import com.mikeyphw.bkx.android.core.runs.RunSummary
import com.mikeyphw.bkx.android.core.runs.RunSummaryStore
import com.mikeyphw.bkx.android.core.transfer.TransferCollisionSummary
import com.mikeyphw.bkx.android.core.transfer.TransferEndpoint
import com.mikeyphw.bkx.android.core.transfer.TransferMode
import com.mikeyphw.bkx.android.core.transfer.TransferPreviewSummary
import com.mikeyphw.bkx.android.core.transfer.TransferRequestBuilder
import com.mikeyphw.bkx.android.core.transfer.TransferTopologyDraft
import com.mikeyphw.bkx.android.core.transfer.TransferVerificationPlan
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

fun interface AutomationProgressSink {
    fun onStage(label: String)

    companion object {
        val None = AutomationProgressSink { }
    }
}

interface AutomationOperationPort {
    suspend fun execute(request: BridgeRequestEnvelope, recordInHistory: Boolean): OperationOutcome
    suspend fun cancel(requestId: String, cancelRequestId: String): OperationOutcome
    suspend fun snapshot(): OperationRuntimeState
    suspend fun recentRuns(limit: Int = 100): List<RunSummary>
}

class CoordinatorAutomationOperationPort(
    private val coordinator: BkxOperationCoordinator,
    private val runSummaryStore: RunSummaryStore,
) : AutomationOperationPort {
    override suspend fun execute(request: BridgeRequestEnvelope, recordInHistory: Boolean): OperationOutcome =
        coordinator.execute(request, recordInHistory, manageForeground = false)

    override suspend fun cancel(requestId: String, cancelRequestId: String): OperationOutcome = coordinator.cancel(requestId, cancelRequestId, manageForeground = false)
    override suspend fun snapshot(): OperationRuntimeState = coordinator.snapshot()
    override suspend fun recentRuns(limit: Int): List<RunSummary> = runSummaryStore.recentRuns(limit)
}

fun interface ManagedDriveRefreshPort {
    suspend fun refreshIfNeeded(locations: Collection<String>): String?
}

class GoogleManagedDriveRefreshPort(
    private val manager: GoogleDriveAuthorizationManager,
) : ManagedDriveRefreshPort {
    override suspend fun refreshIfNeeded(locations: Collection<String>): String? {
        if (locations.none { it.startsWith(GoogleDriveRcloneConfig.rootUri, ignoreCase = true) }) return null
        return suspendCancellableCoroutine { continuation ->
            manager.authorize { outcome ->
                if (!continuation.isActive) return@authorize
                continuation.resume(
                    when (outcome) {
                        GoogleDriveAuthorizationOutcome.Connected -> null
                        is GoogleDriveAuthorizationOutcome.NeedsConsent -> "Google Drive consent is required. Open BKX → More → Google Drive and reconnect before running this automation."
                        is GoogleDriveAuthorizationOutcome.Failed -> outcome.message
                    },
                )
            }
        }
    }
}

class BkxAutomationEngine(
    private val operationPort: AutomationOperationPort,
    private val stateStore: AutomationStatePort,
    private val driveRefreshPort: ManagedDriveRefreshPort,
) {
    private val executions = ConcurrentHashMap<String, CompletableDeferred<AutomationResult>>()
    private val backupBuilder = BackupRequestBuilder()
    private val restoreBuilder = RestoreRequestBuilder()
    private val archiveBuilder = ArchiveRequestBuilder()
    private val transferBuilder = TransferRequestBuilder()

    suspend fun execute(rawRequest: AutomationRequest, progress: AutomationProgressSink = AutomationProgressSink.None): AutomationResult {
        val request = AutomationIntentCodec.normalizeRequestId(rawRequest)
        request.validate().firstOrNull()?.let { message ->
            return terminalFailure(request, "automation_invalid_request", message)
        }
        if (request.action !in setOf(AutomationAction.Status, AutomationAction.Cancel)) {
            stateStore.terminal(request.requestId)?.let { return it }
        }

        val candidate = CompletableDeferred<AutomationResult>()
        val existing = executions.putIfAbsent(request.requestId, candidate)
        if (existing != null) return existing.await()
        if (request.action !in setOf(AutomationAction.Status, AutomationAction.Cancel) && stateStore.activeStage(request.requestId) != null) {
            executions.remove(request.requestId, candidate)
            val interrupted = terminalFailure(
                request,
                "automation_previous_dispatch_incomplete",
                "A previous automation with this request ID did not commit a terminal result. Check Status or retry with a new request ID.",
            )
            candidate.complete(interrupted)
            return interrupted
        }
        val result = try {
            when (request.action) {
                AutomationAction.Backup -> runBackup(request, progress)
                AutomationAction.Restore -> runRestore(request, progress)
                AutomationAction.Verify -> runVerify(request, progress)
                AutomationAction.ArchiveList -> runArchiveList(request, progress)
                AutomationAction.ArchiveInfo -> runArchiveInfo(request, progress)
                AutomationAction.ArchiveBrowse -> runArchiveBrowse(request, progress)
                AutomationAction.Transfer -> runTransfer(request, progress)
                AutomationAction.Cancel -> runCancel(request, progress)
                AutomationAction.Status -> runStatus(request)
            }
        } catch (error: Throwable) {
            terminalFailure(request, "automation_exception", error.message ?: error::class.java.simpleName)
        } finally {
            executions.remove(request.requestId, candidate)
        }
        if (request.action !in setOf(AutomationAction.Status, AutomationAction.Cancel) || result.status !in setOf("running", "unknown")) {
            stateStore.complete(result)
        }
        candidate.complete(result)
        return result
    }

    private suspend fun runBackup(request: AutomationRequest, progress: AutomationProgressSink): AutomationResult {
        refreshManagedDrive(request, listOf(request.destination))?.let { return it }
        val compression = when (request.compression.lowercase()) {
            "none" -> BackupCompression.None
            "zstd" -> BackupCompression.Zstd
            else -> BackupCompression.Gzip
        }
        val draft = BackupPlanDraft(
            source = BackupSourceSelection("automation-source", request.source.label(), request.source),
            destination = BackupDestinationSelection("automation-destination", request.destination.label(), request.destination),
            archiveName = request.archiveName,
            filters = BackupFilterRules(request.includePatterns, request.excludePatterns, request.includeHidden, followSymlinks = false),
            compression = compression,
            split = request.splitChunkSizeBytes?.let { BackupSplitPolicy(true, it) } ?: BackupSplitPolicy(),
            verification = BackupVerificationPolicy(request.verifyAfter),
        )
        progress.onStage("Planning backup")
        val planId = stageId(request.requestId, "backup-plan")
        val plan = runStage(request, planId, backupBuilder.buildPlanRequest(planId, draft), false) ?: return stateStore.terminal(request.requestId)!!
        if (plan.status != BridgeResultStatus.Success) return terminalFromBridge(request, plan)
        val preview = BackupPlanPreview(
            requestId = plan.requestId,
            planToken = plan.artifacts.firstOrNull { it.label == "backup_plan" }?.sha256 ?: plan.requestId,
            draftFingerprint = draft.fingerprint(),
            filesTotal = plan.metrics?.filesTotal,
            bytesTotal = plan.metrics?.bytesTotal,
            warnings = plan.errors.map { BackupPlanWarning(it.code, it.message, BackupWarningSeverity.Warning) },
            verificationExpectations = if (draft.verification.verifyAfterCreate) listOf(BackupVerificationExpectation("sha256", "Verify the completed archive before reporting success.")) else emptyList(),
        )
        progress.onStage("Creating backup")
        val createId = stageId(request.requestId, "backup-create")
        val result = runStage(request, createId, backupBuilder.buildCreateRequest(createId, draft, preview), true) ?: return stateStore.terminal(request.requestId)!!
        return terminalFromBridge(request, result)
    }

    private suspend fun runRestore(request: AutomationRequest, progress: AutomationProgressSink): AutomationResult {
        refreshManagedDrive(request, listOf(request.archive))?.let { return it }
        val draft = RestoreDraft(
            archive = archiveLocation(request.archive),
            destination = RestoreDestinationSelection("automation-destination", request.destination.label(), request.destination),
            subset = if (request.restoreSubset.isEmpty()) RestoreSubsetSelection.All else RestoreSubsetSelection.Paths(request.restoreSubset),
            outputMode = if (request.restoreMode == "existing_directory") RestoreOutputMode.ExistingDirectory else RestoreOutputMode.FreshDirectory,
            verifyBeforeRestore = request.verifyAfter,
        )
        progress.onStage("Previewing restore")
        val previewId = stageId(request.requestId, "restore-preview")
        val previewResult = runStage(request, previewId, restoreBuilder.buildPreviewRequest(previewId, draft), false) ?: return stateStore.terminal(request.requestId)!!
        if (previewResult.status != BridgeResultStatus.Success) return terminalFromBridge(request, previewResult)
        val preview = previewResult.toRestorePreviewSummary(draft)
        val confirmationRequired = preview.requiresDestructiveConfirmation || RestoreDestinationPolicy.requiresRootConfirmation(request.destination)
        val confirmation = if (confirmationRequired) {
            val required = RestoreExtractionConfirmation.requiredPhrase(draft)
            if (request.restoreConfirmation != required) {
                return terminalFailure(request, "restore_confirmation_required", "Restore requires exact confirmation '$required'.")
            }
            RestoreExtractionConfirmation(
                draftFingerprint = draft.fingerprint(),
                planToken = preview.planToken,
                acknowledgedOverwriteCount = preview.overwriteSummary.totalOverwrites,
                confirmationPhrase = required,
                confirmedAtEpochMillis = System.currentTimeMillis(),
            )
        } else null
        progress.onStage("Restoring archive")
        val extractId = stageId(request.requestId, "restore-extract")
        val result = runStage(request, extractId, restoreBuilder.buildExtractRequest(extractId, draft, preview, confirmation), true) ?: return stateStore.terminal(request.requestId)!!
        return terminalFromBridge(request, result)
    }

    private suspend fun runVerify(request: AutomationRequest, progress: AutomationProgressSink): AutomationResult {
        refreshManagedDrive(request, listOf(request.archive))?.let { return it }
        progress.onStage("Verifying archive")
        val stageId = stageId(request.requestId, "archive-verify")
        val result = runStage(request, stageId, archiveBuilder.buildVerifyRequest(stageId, archiveLocation(request.archive)), true) ?: return stateStore.terminal(request.requestId)!!
        return terminalFromBridge(request, result)
    }

    private suspend fun runArchiveList(request: AutomationRequest, progress: AutomationProgressSink): AutomationResult {
        refreshManagedDrive(request, request.roots)?.let { return it }
        progress.onStage("Listing archives")
        val stageId = stageId(request.requestId, "archive-list")
        val result = runStage(request, stageId, archiveBuilder.buildListRequest(stageId, request.roots, request.maxDepth, request.limit), false) ?: return stateStore.terminal(request.requestId)!!
        return terminalFromBridge(request, result)
    }

    private suspend fun runArchiveInfo(request: AutomationRequest, progress: AutomationProgressSink): AutomationResult {
        refreshManagedDrive(request, listOf(request.archive))?.let { return it }
        progress.onStage("Reading archive info")
        val stageId = stageId(request.requestId, "archive-info")
        val result = runStage(request, stageId, archiveBuilder.buildInfoRequest(stageId, archiveLocation(request.archive)), false) ?: return stateStore.terminal(request.requestId)!!
        return terminalFromBridge(request, result)
    }

    private suspend fun runArchiveBrowse(request: AutomationRequest, progress: AutomationProgressSink): AutomationResult {
        refreshManagedDrive(request, listOf(request.archive))?.let { return it }
        progress.onStage("Browsing archive")
        val stageId = stageId(request.requestId, "archive-browse")
        val result = runStage(
            request,
            stageId,
            archiveBuilder.buildBrowseRequest(stageId, ArchiveBrowsePageRequest(archiveLocation(request.archive), request.pageToken.ifBlank { null }, request.pageSize)),
            false,
        ) ?: return stateStore.terminal(request.requestId)!!
        return terminalFromBridge(request, result)
    }

    private suspend fun runTransfer(request: AutomationRequest, progress: AutomationProgressSink): AutomationResult {
        val sourceLocations = splitAutomationList(request.source).ifEmpty { listOf(request.source) }
        refreshManagedDrive(request, sourceLocations + request.destination)?.let { return it }
        val mode = when (request.transferMode) { "pull" -> TransferMode.Pull; "sync" -> TransferMode.Sync; else -> TransferMode.Push }
        val draft = TransferTopologyDraft(
            mode = mode,
            sources = sourceLocations.mapIndexed { index, location -> TransferEndpoint("automation-source-$index", location.label(), location) },
            destination = TransferEndpoint("automation-destination", request.destination.label(), request.destination),
            verifyAfterTransfer = request.verifyAfter,
            checksum = request.checksum.ifBlank { null },
            overwriteExisting = request.overwrite,
            deleteExtraneousOnSync = request.deleteExtraneous,
        )
        progress.onStage("Planning transfer")
        val planId = stageId(request.requestId, "transfer-plan")
        val plan = runStage(request, planId, transferBuilder.buildPreviewRequest(planId, draft), false) ?: return stateStore.terminal(request.requestId)!!
        if (plan.status != BridgeResultStatus.Success) return terminalFromBridge(request, plan)
        val preview = TransferPreviewSummary(
            requestId = plan.requestId,
            draftFingerprint = draft.fingerprint(),
            topologyLabel = "${draft.mode.displayName} · ${draft.sources.size} source(s) → ${draft.destination?.label ?: "destination"}",
            mappings = emptyList(),
            collisionSummary = TransferCollisionSummary(
                collisions = plan.errors.filter { it.code.contains("collision", true) }.map { it.message },
                overlaps = plan.errors.filter { it.code.contains("overlap", true) }.map { it.message },
                symlinkPolicy = draft.symlinkPolicy,
                specialFilePolicy = draft.specialFilePolicy,
            ),
            verificationPlan = TransferVerificationPlan(draft.verifyAfterTransfer, draft.checksum),
            warnings = plan.errors.filterNot { it.code.contains("collision", true) || it.code.contains("overlap", true) }.map { it.message },
        )
        if (!preview.isExecutable) return terminalFailure(request, "transfer_preview_blocked", "Transfer preview found collisions or overlapping paths.")
        progress.onStage("Running transfer")
        val runId = stageId(request.requestId, "transfer-run")
        val result = runStage(request, runId, transferBuilder.buildRunRequest(runId, draft, preview), true) ?: return stateStore.terminal(request.requestId)!!
        return terminalFromBridge(request, result)
    }

    private suspend fun runCancel(request: AutomationRequest, progress: AutomationProgressSink): AutomationResult {
        progress.onStage("Cancelling operation")
        val stage = stateStore.activeStage(request.targetRequestId) ?: request.targetRequestId
        return when (val outcome = operationPort.cancel(stage, stageId(request.requestId, "cancel"))) {
            is OperationOutcome.CancellationAccepted -> AutomationResult(request.requestId, request.action, "accepted", stage, "Cancellation requested.")
            is OperationOutcome.Failed -> AutomationResult(request.requestId, request.action, "failure", stage, outcome.failure.title, outcome.failure.code, outcome.failure.message)
            is OperationOutcome.Completed -> terminalFromBridge(request, outcome.result)
        }
    }

    private suspend fun runStatus(request: AutomationRequest): AutomationResult {
        stateStore.terminal(request.targetRequestId)?.let { return it.copy(requestId = request.requestId, action = request.action) }
        val stage = stateStore.activeStage(request.targetRequestId) ?: request.targetRequestId
        operationPort.snapshot().activeFor(stage)?.let { active ->
            return AutomationResult(
                requestId = request.requestId,
                action = request.action,
                status = active.status.name.lowercase(),
                operationRequestId = active.requestId,
                summary = active.lastMessage,
                filesTotal = active.filesTotal,
                bytesTotal = active.bytesTotal,
            )
        }
        operationPort.recentRuns().firstOrNull { it.requestId == stage }?.let { run ->
            return AutomationResult(request.requestId, request.action, run.status.name.lowercase(), run.requestId, run.summary)
        }
        return AutomationResult(request.requestId, request.action, "unknown", stage, "No BKX operation or automation receipt was found for ${request.targetRequestId}.")
    }

    private suspend fun runStage(
        automation: AutomationRequest,
        stageId: String,
        bridgeRequest: BridgeRequestEnvelope,
        recordInHistory: Boolean,
    ): BridgeResultEnvelope? {
        stateStore.markActive(automation.requestId, stageId)
        return when (val outcome = operationPort.execute(bridgeRequest, recordInHistory)) {
            is OperationOutcome.Completed -> outcome.result
            is OperationOutcome.Failed -> {
                stateStore.complete(AutomationResult(automation.requestId, automation.action, "failure", stageId, outcome.failure.title, outcome.failure.code, outcome.failure.message))
                null
            }
            is OperationOutcome.CancellationAccepted -> {
                stateStore.complete(AutomationResult(automation.requestId, automation.action, "accepted", stageId, "Cancellation requested."))
                null
            }
        }
    }

    private suspend fun refreshManagedDrive(request: AutomationRequest, locations: Collection<String>): AutomationResult? {
        val error = driveRefreshPort.refreshIfNeeded(locations) ?: return null
        return terminalFailure(request, "google_drive_authorization_required", error)
    }

    private fun terminalFromBridge(request: AutomationRequest, result: BridgeResultEnvelope): AutomationResult {
        val status = when (result.status) {
            BridgeResultStatus.Success -> "success"
            BridgeResultStatus.Failure -> "failure"
            BridgeResultStatus.Cancelled -> "cancelled"
        }
        return AutomationResult(
            requestId = request.requestId,
            action = request.action,
            status = status,
            operationRequestId = result.requestId,
            summary = result.summary,
            errorCode = result.errors.firstOrNull()?.code.orEmpty(),
            errorMessage = result.errors.firstOrNull()?.message.orEmpty(),
            artifactPaths = result.artifacts.map { it.path },
            artifactSha256 = result.artifacts.mapNotNull { it.sha256 },
            filesTotal = result.metrics?.filesTotal,
            bytesTotal = result.metrics?.bytesTotal,
        ).also(stateStore::complete)
    }

    private fun terminalFailure(request: AutomationRequest, code: String, message: String): AutomationResult = AutomationResult(
        requestId = request.requestId.ifBlank { "automation-invalid" },
        action = request.action,
        status = "failure",
        summary = message,
        errorCode = code,
        errorMessage = message,
    ).also(stateStore::complete)

    private fun archiveLocation(location: String): ArchiveLocation = ArchiveLocation(
        archiveId = "automation-archive",
        displayName = location.trimEnd('/').substringAfterLast('/').ifBlank { "BKX archive" },
        stagedPath = location,
    )

    private fun stageId(requestId: String, stage: String): String = "$requestId-$stage"
    private fun String.label(): String = trimEnd('/').substringAfterLast('/').substringAfterLast(':').ifBlank { this }
}
