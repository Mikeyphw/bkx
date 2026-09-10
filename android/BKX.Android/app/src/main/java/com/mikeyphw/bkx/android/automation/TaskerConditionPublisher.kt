package com.mikeyphw.bkx.android.automation

import android.content.Context
import com.joaomgcd.taskerpluginlibrary.condition.TaskerPluginRunnerCondition
import com.mikeyphw.bkx.android.core.bridge.BridgeRequestEnvelope
import com.mikeyphw.bkx.android.core.bridge.BridgeResultEnvelope
import com.mikeyphw.bkx.android.core.bridge.BridgeResultStatus
import com.mikeyphw.bkx.android.core.operations.ActiveOperation
import com.mikeyphw.bkx.android.core.operations.OperationFailureUiState
import kotlinx.serialization.json.JsonPrimitive

class BkxTaskerConditionPublisher(
    context: Context,
    private val store: BkxTaskerConditionStore = SharedPreferencesBkxTaskerConditionStore(context),
) {
    private val appContext = context.applicationContext ?: context

    fun operationStarted(request: BridgeRequestEnvelope, operation: ActiveOperation) {
        val signal = signalFor(request, operation, BkxTaskerEventType.OperationStarted.id)
        store.update { it.copy(activeSignals = it.activeSignals + (operation.requestId to signal)) }
        publish(signal)
        if (request.operation == "jobs.run") publish(signal.copy(event = BkxTaskerEventType.JobStarted.id))
        notifyStateChanged()
    }

    fun operationProgress(request: BridgeRequestEnvelope, operation: ActiveOperation) {
        val bucket = BkxTaskerConditionMatcher.canonicalProgressBucket(operation.progress) ?: return
        var shouldPublish = false
        store.update { current ->
            val previous = current.lastProgressBucket[operation.requestId]
            if (previous == null || bucket > previous) shouldPublish = true
            current.copy(lastProgressBucket = current.lastProgressBucket + (operation.requestId to bucket))
        }
        if (shouldPublish) {
            val signal = signalFor(request, operation, BkxTaskerEventType.ProgressMilestone.id, progressOverride = bucket)
            store.update { it.copy(activeSignals = it.activeSignals + (operation.requestId to signal)) }
            publish(signal)
        }
    }

    fun operationTerminal(request: BridgeRequestEnvelope, operation: ActiveOperation, result: BridgeResultEnvelope) {
        val terminal = signalFor(
            request = request,
            operation = operation,
            event = BkxTaskerEventType.OperationCompleted.id,
            result = result,
        ).copy(terminal = true)
        rememberTerminal(terminal)
        publish(terminal)
        when (result.status) {
            BridgeResultStatus.Success -> {
                successAliases(request.operation).forEach { publish(terminal.copy(event = it, terminal = false)) }
                if (request.operation == "jobs.run" && terminal.snapshotId.isNotBlank()) {
                    publish(terminal.copy(event = BkxTaskerEventType.SnapshotCreated.id, terminal = false))
                }
                archiveDisplayName(result)?.let { publish(terminal.copy(event = BkxTaskerEventType.ArchiveCreated.id, archive = it, terminal = false)) }
                if (request.operation == "repository.check" && terminal.repositoryId.isNotBlank()) {
                    updateRepositoryHealth(terminal.repositoryId, healthy = true)
                }
                markRemotesRecovered(terminal)
            }
            BridgeResultStatus.Failure -> {
                publish(terminal.copy(event = BkxTaskerEventType.OperationFailed.id, terminal = false))
                if (request.operation == "jobs.run") publish(terminal.copy(event = BkxTaskerEventType.JobFailed.id, terminal = false))
                if (request.operation == "repository.check") {
                    publish(terminal.copy(event = BkxTaskerEventType.RepositoryCheckFailed.id, terminal = false))
                    if (terminal.repositoryId.isNotBlank()) {
                        updateRepositoryHealth(terminal.repositoryId, healthy = false)
                        publish(terminal.copy(event = BkxTaskerEventType.RepositoryNeedsAttention.id, terminal = false))
                    }
                }
                markRemotesUnavailableIfApplicable(terminal)
            }
            BridgeResultStatus.Cancelled -> {
                publish(terminal.copy(event = BkxTaskerEventType.OperationCancelled.id, terminal = false))
                if (request.operation == "jobs.run") publish(terminal.copy(event = BkxTaskerEventType.JobCancelled.id, terminal = false))
            }
        }
        notifyStateChanged()
    }

    fun operationFailed(request: BridgeRequestEnvelope, operation: ActiveOperation?, failure: OperationFailureUiState) {
        val now = System.currentTimeMillis()
        val signal = BkxTaskerSignal(
            event = BkxTaskerEventType.OperationCompleted.id,
            operation = request.operation,
            requestId = request.requestId,
            status = "failure",
            progressPercent = BkxTaskerConditionMatcher.canonicalProgressBucket(operation?.progress),
            filesDone = operation?.filesDone,
            filesTotal = operation?.filesTotal,
            bytesDone = operation?.bytesDone,
            bytesTotal = operation?.bytesTotal,
            remotes = remoteIdentities(request),
            errorCode = failure.code,
            error = sanitize(failure.message),
            phase = operation?.lastPhase.orEmpty(),
            timestampEpochMillis = now,
            terminal = true,
            jobId = request.payloadString("job_id"),
            runId = request.requestId,
            repositoryId = request.repositoryIdentity(),
            snapshotId = request.payloadString("snapshot"),
        )
        rememberTerminal(signal)
        publish(signal)
        publish(signal.copy(event = BkxTaskerEventType.OperationFailed.id, terminal = false))
        if (request.operation == "jobs.run") publish(signal.copy(event = BkxTaskerEventType.JobFailed.id, terminal = false))
        if (request.operation == "repository.check") {
            publish(signal.copy(event = BkxTaskerEventType.RepositoryCheckFailed.id, terminal = false))
            if (signal.repositoryId.isNotBlank()) {
                updateRepositoryHealth(signal.repositoryId, healthy = false)
                publish(signal.copy(event = BkxTaskerEventType.RepositoryNeedsAttention.id, terminal = false))
            }
        }
        markRemotesUnavailableIfApplicable(signal)
        notifyStateChanged()
    }

    fun operationInterrupted(operation: ActiveOperation, failure: OperationFailureUiState) {
        val previous = store.read().activeSignals[operation.requestId]
        val signal = BkxTaskerSignal(
            event = BkxTaskerEventType.OperationCompleted.id,
            operation = operation.operation,
            requestId = operation.requestId,
            status = "interrupted",
            progressPercent = BkxTaskerConditionMatcher.canonicalProgressBucket(operation.progress),
            filesDone = operation.filesDone,
            filesTotal = operation.filesTotal,
            bytesDone = operation.bytesDone,
            bytesTotal = operation.bytesTotal,
            errorCode = failure.code,
            error = sanitize(failure.message),
            phase = "interrupted",
            timestampEpochMillis = System.currentTimeMillis(),
            terminal = true,
            jobId = previous?.jobId.orEmpty(),
            runId = operation.requestId,
            repositoryId = previous?.repositoryId.orEmpty(),
            snapshotId = previous?.snapshotId.orEmpty(),
        )
        rememberTerminal(signal)
        publish(signal)
        publish(signal.copy(event = BkxTaskerEventType.OperationFailed.id, terminal = false))
        if (signal.jobId.isNotBlank()) publish(signal.copy(event = BkxTaskerEventType.JobFailed.id, terminal = false))
        notifyStateChanged()
    }

    fun driveConnecting() {
        store.update { it.copy(driveState = BkxTaskerDriveState.Connecting) }
        notifyStateChanged()
    }
    fun driveConnected() {
        val wasUnavailable = store.read().remoteAvailability["google-drive"] == false
        store.update { it.copy(driveState = BkxTaskerDriveState.Connected, remoteAvailability = it.remoteAvailability + ("google-drive" to true)) }
        if (wasUnavailable) publish(remoteSignal(BkxTaskerEventType.RemoteRecovered.id, "google-drive"))
        notifyStateChanged()
    }
    fun driveNeedsAuthorization(message: String = "Google Drive authorization is required.") {
        store.update { it.copy(driveState = BkxTaskerDriveState.NeedsAuthorization, remoteAvailability = it.remoteAvailability + ("google-drive" to false)) }
        publish(remoteSignal(BkxTaskerEventType.GoogleDriveAuthorizationRequired.id, "google-drive", "BKX_GOOGLE_DRIVE_AUTH_REQUIRED", message))
        notifyStateChanged()
    }
    fun driveError(message: String) {
        val wasUnavailable = store.read().remoteAvailability["google-drive"] == false
        store.update { it.copy(driveState = BkxTaskerDriveState.Error, remoteAvailability = it.remoteAvailability + ("google-drive" to false)) }
        if (!wasUnavailable) publish(remoteSignal(BkxTaskerEventType.RemoteUnavailable.id, "google-drive", "BKX_REMOTE_UNAVAILABLE", message))
        notifyStateChanged()
    }
    fun driveDisconnected() {
        store.update { it.copy(driveState = BkxTaskerDriveState.Disconnected, remoteAvailability = it.remoteAvailability + ("google-drive" to false)) }
        notifyStateChanged()
    }

    private fun signalFor(
        request: BridgeRequestEnvelope,
        operation: ActiveOperation,
        event: String,
        result: BridgeResultEnvelope? = null,
        progressOverride: Int? = null,
    ): BkxTaskerSignal = BkxTaskerSignal(
        event = event,
        operation = request.operation,
        requestId = request.requestId,
        status = result?.status?.name?.lowercase() ?: operation.status.name.lowercase(),
        progressPercent = progressOverride ?: BkxTaskerConditionMatcher.canonicalProgressBucket(operation.progress),
        filesDone = operation.filesDone,
        filesTotal = operation.filesTotal ?: result?.metrics?.filesTotal,
        bytesDone = operation.bytesDone,
        bytesTotal = operation.bytesTotal ?: result?.metrics?.bytesTotal,
        archive = result?.let(::archiveDisplayName).orEmpty(),
        remotes = remoteIdentities(request),
        errorCode = result?.errors?.firstOrNull()?.code.orEmpty(),
        error = result?.errors?.firstOrNull()?.message?.let(::sanitize).orEmpty(),
        phase = operation.lastPhase,
        timestampEpochMillis = System.currentTimeMillis(),
        jobId = request.payloadString("job_id"),
        runId = request.requestId,
        repositoryId = result?.repositoryIdentity().orEmpty().ifBlank { request.repositoryIdentity() },
        snapshotId = result?.snapshotIdentity().orEmpty().ifBlank { request.payloadString("snapshot") },
    )

    private fun rememberTerminal(signal: BkxTaskerSignal) {
        store.update { current -> current.copy(
            activeSignals = current.activeSignals - signal.requestId,
            terminalHistory = listOf(signal) + current.terminalHistory.filterNot { it.requestId == signal.requestId },
            lastProgressBucket = current.lastProgressBucket - signal.requestId,
        ) }
    }

    private fun publish(signal: BkxTaskerSignal) {
        TaskerPluginRunnerCondition.requestQuery(appContext, BkxTaskerEventEditActivity::class.java, signal.toUpdate())
    }

    private fun notifyStateChanged() {
        TaskerPluginRunnerCondition.requestQuery(appContext, BkxTaskerStateEditActivity::class.java)
    }

    private fun markRemotesUnavailableIfApplicable(signal: BkxTaskerSignal) {
        if (signal.remotes.none { it != "local" }) return
        if (!isRemoteFailure(signal.errorCode, signal.error)) return
        signal.remotes.filter { it != "local" }.forEach { remote ->
            val wasUnavailable = store.read().remoteAvailability[remote] == false
            store.update { it.copy(remoteAvailability = it.remoteAvailability + (remote to false)) }
            if (!wasUnavailable) publish(remoteSignal(BkxTaskerEventType.RemoteUnavailable.id, remote, signal.errorCode, signal.error))
        }
    }

    private fun markRemotesRecovered(signal: BkxTaskerSignal) {
        signal.remotes.filter { it != "local" }.forEach { remote ->
            val wasUnavailable = store.read().remoteAvailability[remote] == false
            store.update { it.copy(remoteAvailability = it.remoteAvailability + (remote to true)) }
            if (wasUnavailable) publish(remoteSignal(BkxTaskerEventType.RemoteRecovered.id, remote))
        }
    }

    private fun remoteSignal(event: String, remote: String, code: String = "", message: String = "") = BkxTaskerSignal(
        event = event,
        remotes = listOf(remote),
        errorCode = code,
        error = sanitize(message),
        phase = "remote",
        timestampEpochMillis = System.currentTimeMillis(),
    )

    private fun remoteIdentities(request: BridgeRequestEnvelope): List<String> {
        val text = request.payload.toString()
        val remotes = Regex("rclone://([^/\\\"']+)", RegexOption.IGNORE_CASE).findAll(text).map { match ->
            val name = match.groupValues[1]
            if (name.equals("bkx-google-drive", true)) "google-drive" else "rclone:$name"
        }.toMutableSet()
        if (remotes.isEmpty()) remotes += "local"
        return remotes.toList().sorted()
    }

    private fun archiveDisplayName(result: BridgeResultEnvelope): String? = result.artifacts.firstOrNull { artifact ->
        artifact.label.contains("archive", true) || archiveSuffixes.any { suffix -> artifact.path.lowercase().endsWith(suffix) }
    }?.path?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() }

    private fun successAliases(operation: String): List<String> = buildList {
        when (operation) {
            "backup.create" -> add(BkxTaskerEventType.BackupCompleted.id)
            "restore.extract", "snapshots.restore" -> add(BkxTaskerEventType.RestoreCompleted.id)
            "archive.verify" -> add(BkxTaskerEventType.VerifyCompleted.id)
            "transfer.run" -> add(BkxTaskerEventType.TransferCompleted.id)
            "jobs.run" -> add(BkxTaskerEventType.JobCompleted.id)
            "backup.snapshot" -> {
                add(BkxTaskerEventType.BackupCompleted.id)
                add(BkxTaskerEventType.SnapshotCreated.id)
            }
            "repository.check" -> add(BkxTaskerEventType.RepositoryCheckPassed.id)
            "retention.apply" -> add(BkxTaskerEventType.RetentionApplied.id)
            "repository.prune" -> add(BkxTaskerEventType.RepositoryPruned.id)
        }
    }

    private fun updateRepositoryHealth(repositoryId: String, healthy: Boolean) {
        store.update { current ->
            current.copy(repositoryHealth = current.repositoryHealth + (repositoryId to healthy))
        }
        notifyStateChanged()
    }

    private fun BridgeRequestEnvelope.payloadString(key: String): String =
        (payload[key] as? JsonPrimitive)?.content.orEmpty()

    private fun BridgeRequestEnvelope.repositoryIdentity(): String =
        payloadString("repository_id").ifBlank { payloadString("repository_ref") }

    private fun BridgeResultEnvelope.repositoryIdentity(): String = artifacts.asSequence()
        .mapNotNull { it.metadata["repository_id"] }
        .firstOrNull()
        .orEmpty()

    private fun BridgeResultEnvelope.snapshotIdentity(): String = artifacts.asSequence()
        .mapNotNull { artifact ->
            artifact.metadata["snapshot_id"]
                ?: artifact.path.substringAfterLast("/snapshots/", "").takeIf { it.isNotBlank() }
        }
        .firstOrNull()
        .orEmpty()

    private fun isRemoteFailure(code: String, message: String): Boolean {
        val value = "$code $message".lowercase()
        return listOf("remote", "network", "auth", "oauth", "drive", "timeout", "connection", "dns").any(value::contains)
    }

    private fun sanitize(value: String): String = value
        .replace(Regex("(?i)(access[_ -]?token|refresh[_ -]?token|authorization|password|secret)\\s*[:=]\\s*\\S+"), "$1=<redacted>")
        .replace(Regex("/data/(?:data|user/\\d+)/[^ ]+"), "private path")
        .replace(Regex("/storage/[^ ]+"), "storage path")
        .take(500)

    private companion object {
        val archiveSuffixes = listOf(".zip", ".tar", ".tar.gz", ".tgz", ".tar.bz2", ".tbz2", ".tar.zst", ".tzst")
    }
}
