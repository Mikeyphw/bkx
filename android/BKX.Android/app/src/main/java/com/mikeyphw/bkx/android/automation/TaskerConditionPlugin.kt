package com.mikeyphw.bkx.android.automation

import android.content.Context
import com.joaomgcd.taskerpluginlibrary.condition.TaskerPluginRunnerConditionEvent
import com.joaomgcd.taskerpluginlibrary.condition.TaskerPluginRunnerConditionState
import com.joaomgcd.taskerpluginlibrary.config.TaskerPluginConfig
import com.joaomgcd.taskerpluginlibrary.config.TaskerPluginConfigHelper
import com.joaomgcd.taskerpluginlibrary.input.TaskerInput
import com.joaomgcd.taskerpluginlibrary.runner.TaskerPluginResultCondition
import com.joaomgcd.taskerpluginlibrary.runner.TaskerPluginResultConditionSatisfied
import com.joaomgcd.taskerpluginlibrary.runner.TaskerPluginResultConditionUnknown
import com.joaomgcd.taskerpluginlibrary.runner.TaskerPluginResultConditionUnsatisfied
import com.mikeyphw.bkx.android.app.di.DefaultBackendGraph
import com.mikeyphw.bkx.android.core.operations.ActiveOperationStatus
import kotlinx.coroutines.runBlocking

class BkxTaskerEventRunner : TaskerPluginRunnerConditionEvent<BkxTaskerEventInput, BkxTaskerConditionOutput, BkxTaskerEventUpdate>() {
    override fun getSatisfiedCondition(
        context: Context,
        input: TaskerInput<BkxTaskerEventInput>,
        update: BkxTaskerEventUpdate?,
    ): TaskerPluginResultCondition<BkxTaskerConditionOutput> {
        val signal = update?.toSignal() ?: return TaskerPluginResultConditionUnsatisfied()
        return if (BkxTaskerConditionMatcher.eventMatches(input.regular, signal)) {
            TaskerPluginResultConditionSatisfied(context, signal.toOutput())
        } else {
            TaskerPluginResultConditionUnsatisfied()
        }
    }
}

class BkxTaskerStateRunner : TaskerPluginRunnerConditionState<BkxTaskerStateInput, BkxTaskerConditionOutput>() {
    override fun getSatisfiedCondition(
        context: Context,
        input: TaskerInput<BkxTaskerStateInput>,
        update: Unit?,
    ): TaskerPluginResultCondition<BkxTaskerConditionOutput> {
        val config = input.regular
        val type = BkxTaskerStateType.fromId(config.stateType)
        val snapshot = SharedPreferencesBkxTaskerConditionStore(context).read()
        val runtime = runCatching { runBlocking { DefaultBackendGraph.operationStateStore(context).read() } }.getOrNull()

        fun satisfied(signal: BkxTaskerSignal) = TaskerPluginResultConditionSatisfied(
            context,
            signal.toOutput(state = type.id),
        )

        fun running(operationPrefix: String? = null): TaskerPluginResultCondition<BkxTaskerConditionOutput> {
            val active = runtime?.activeOperations?.values.orEmpty()
                .filter { it.status in ACTIVE_STATUSES }
                .filter { operationPrefix == null || it.operation.startsWith(operationPrefix) }
                .sortedByDescending { it.lastSeenEpochMillis }
                .firstOrNull { operation ->
                    val signal = snapshot.activeSignals[operation.requestId] ?: operation.toSignal()
                    BkxTaskerConditionMatcher.stateSignalMatches(config, signal)
                }
                ?: return TaskerPluginResultConditionUnsatisfied()
            return satisfied(snapshot.activeSignals[active.requestId] ?: active.toSignal())
        }

        fun lastTerminal(status: String): TaskerPluginResultCondition<BkxTaskerConditionOutput> {
            val signal = snapshot.terminalHistory
                .asSequence()
                .filter { it.status.equals(status, true) || (status == "failure" && it.status == "interrupted") }
                .firstOrNull { BkxTaskerConditionMatcher.stateSignalMatches(config, it) }
                ?: return TaskerPluginResultConditionUnsatisfied()
            return satisfied(signal)
        }

        return when (type) {
            BkxTaskerStateType.AnyOperationRunning -> running()
            BkxTaskerStateType.BackupRunning -> running("backup.")
            BkxTaskerStateType.RestoreRunning -> running("restore.")
            BkxTaskerStateType.VerifyRunning -> running("archive.verify")
            BkxTaskerStateType.TransferRunning -> running("transfer.")
            BkxTaskerStateType.JobRunning -> running("jobs.run")
            BkxTaskerStateType.RepositoryHealthy -> repositoryHealth(snapshot, config, healthy = true)?.let(::satisfied)
                ?: TaskerPluginResultConditionUnsatisfied()
            BkxTaskerStateType.RepositoryNeedsAttention -> repositoryHealth(snapshot, config, healthy = false)?.let(::satisfied)
                ?: TaskerPluginResultConditionUnsatisfied()
            BkxTaskerStateType.LastJobSucceeded -> lastJobTerminal("success", snapshot, config)?.let(::satisfied)
                ?: TaskerPluginResultConditionUnsatisfied()
            BkxTaskerStateType.LastJobFailed -> lastJobTerminal("failure", snapshot, config)?.let(::satisfied)
                ?: TaskerPluginResultConditionUnsatisfied()
            BkxTaskerStateType.LastJobCancelled -> lastJobTerminal("cancelled", snapshot, config)?.let(::satisfied)
                ?: TaskerPluginResultConditionUnsatisfied()
            BkxTaskerStateType.GoogleDriveConnected -> when (snapshot.driveState) {
                BkxTaskerDriveState.Connected -> satisfied(remoteStateSignal("google-drive", "connected"))
                BkxTaskerDriveState.Unknown, BkxTaskerDriveState.Connecting -> TaskerPluginResultConditionUnknown()
                else -> TaskerPluginResultConditionUnsatisfied()
            }
            BkxTaskerStateType.GoogleDriveNeedsAuthorization -> when (snapshot.driveState) {
                BkxTaskerDriveState.NeedsAuthorization -> satisfied(remoteStateSignal("google-drive", "needs_authorization"))
                BkxTaskerDriveState.Unknown, BkxTaskerDriveState.Connecting -> TaskerPluginResultConditionUnknown()
                else -> TaskerPluginResultConditionUnsatisfied()
            }
            BkxTaskerStateType.RemoteAvailable -> {
                val entries = snapshot.remoteAvailability.entries.filter { (remote, _) ->
                    BkxTaskerConditionMatcher.remoteMatches(config.remoteFilter, config.remoteName, listOf(remote))
                }
                when {
                    entries.any { it.value } -> satisfied(remoteStateSignal(entries.first { it.value }.key, "available"))
                    entries.isEmpty() -> TaskerPluginResultConditionUnknown()
                    else -> TaskerPluginResultConditionUnsatisfied()
                }
            }
            BkxTaskerStateType.LastOperationSucceeded -> lastTerminal("success")
            BkxTaskerStateType.LastOperationFailed -> lastTerminal("failure")
            BkxTaskerStateType.LastOperationCancelled -> lastTerminal("cancelled")
        }
    }

    private fun repositoryHealth(
        snapshot: BkxTaskerConditionSnapshot,
        config: BkxTaskerStateInput,
        healthy: Boolean,
    ): BkxTaskerSignal? = snapshot.repositoryHealth.entries
        .asSequence()
        .filter { it.value == healthy }
        .map { (repositoryId, value) ->
            BkxTaskerSignal(
                event = "",
                status = if (value) "healthy" else "needs_attention",
                repositoryId = repositoryId,
                phase = "repository",
                timestampEpochMillis = System.currentTimeMillis(),
            )
        }
        .firstOrNull { BkxTaskerConditionMatcher.stateSignalMatches(config, it) }

    private fun lastJobTerminal(
        status: String,
        snapshot: BkxTaskerConditionSnapshot,
        config: BkxTaskerStateInput,
    ): BkxTaskerSignal? = snapshot.terminalHistory
        .asSequence()
        .filter { it.jobId.isNotBlank() }
        .filter { it.status.equals(status, true) || (status == "failure" && it.status == "interrupted") }
        .firstOrNull { BkxTaskerConditionMatcher.stateSignalMatches(config, it) }

    private fun com.mikeyphw.bkx.android.core.operations.ActiveOperation.toSignal() = BkxTaskerSignal(
        event = "",
        operation = operation,
        requestId = requestId,
        status = status.name.lowercase(),
        progressPercent = BkxTaskerConditionMatcher.canonicalProgressBucket(progress),
        filesDone = filesDone,
        filesTotal = filesTotal,
        bytesDone = bytesDone,
        bytesTotal = bytesTotal,
        phase = lastPhase,
        timestampEpochMillis = lastSeenEpochMillis,
        runId = requestId,
    )

    private fun remoteStateSignal(remote: String, status: String) = BkxTaskerSignal(
        event = "",
        status = status,
        remotes = listOf(remote),
        phase = "remote",
        timestampEpochMillis = System.currentTimeMillis(),
    )

    private companion object {
        val ACTIVE_STATUSES = setOf(ActiveOperationStatus.Queued, ActiveOperationStatus.Running, ActiveOperationStatus.Cancelling)
    }
}

class BkxTaskerEventHelper(config: TaskerPluginConfig<BkxTaskerEventInput>) :
    TaskerPluginConfigHelper<BkxTaskerEventInput, BkxTaskerConditionOutput, BkxTaskerEventRunner>(config) {
    override val inputClass = BkxTaskerEventInput::class.java
    override val runnerClass = BkxTaskerEventRunner::class.java
    override val outputClass = BkxTaskerConditionOutput::class.java
    override val defaultInput = BkxTaskerEventInput()
    override fun addToStringBlurb(input: TaskerInput<BkxTaskerEventInput>, blurbBuilder: StringBuilder) {
        val value = input.regular
        blurbBuilder.clear()
        blurbBuilder.append("BKX • ").append(BkxTaskerEventType.fromId(value.eventType).label)
        if (value.operation.isNotBlank()) blurbBuilder.append(" • ").append(value.operation)
        if (value.jobId.isNotBlank()) blurbBuilder.append(" • job ").append(value.jobId)
        if (value.repositoryId.isNotBlank()) blurbBuilder.append(" • repo ").append(value.repositoryId)
        if (value.remoteFilter != "any") blurbBuilder.append(" • ").append(value.remoteFilter.replace('_', ' '))
    }
}

class BkxTaskerStateHelper(config: TaskerPluginConfig<BkxTaskerStateInput>) :
    TaskerPluginConfigHelper<BkxTaskerStateInput, BkxTaskerConditionOutput, BkxTaskerStateRunner>(config) {
    override val inputClass = BkxTaskerStateInput::class.java
    override val runnerClass = BkxTaskerStateRunner::class.java
    override val outputClass = BkxTaskerConditionOutput::class.java
    override val defaultInput = BkxTaskerStateInput()
    override fun addToStringBlurb(input: TaskerInput<BkxTaskerStateInput>, blurbBuilder: StringBuilder) {
        val value = input.regular
        blurbBuilder.clear()
        blurbBuilder.append("BKX • ").append(BkxTaskerStateType.fromId(value.stateType).label)
        if (value.operation.isNotBlank()) blurbBuilder.append(" • ").append(value.operation)
        if (value.jobId.isNotBlank()) blurbBuilder.append(" • job ").append(value.jobId)
        if (value.repositoryId.isNotBlank()) blurbBuilder.append(" • repo ").append(value.repositoryId)
        if (value.remoteFilter != "any") blurbBuilder.append(" • ").append(value.remoteFilter.replace('_', ' '))
    }
}
