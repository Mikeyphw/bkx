package com.mikeyphw.bkx.android.app.runtime

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.mikeyphw.bkx.android.app.service.BkxOperationForegroundService
import com.mikeyphw.bkx.android.automation.BkxTaskerConditionPublisher
import com.mikeyphw.bkx.android.core.backend.BackendPort
import com.mikeyphw.bkx.android.core.bridge.BridgeFileLayout
import com.mikeyphw.bkx.android.core.bridge.BridgeRequestEnvelope
import com.mikeyphw.bkx.android.core.bridge.BridgeResultEnvelope
import com.mikeyphw.bkx.android.core.bridge.BridgeResultStatus
import com.mikeyphw.bkx.android.core.operations.ActiveOperation
import com.mikeyphw.bkx.android.core.operations.ActiveOperationStatus
import com.mikeyphw.bkx.android.core.operations.OperationFailureMapper
import com.mikeyphw.bkx.android.core.operations.OperationFailureUiState
import com.mikeyphw.bkx.android.core.operations.OperationProgressTracker
import com.mikeyphw.bkx.android.core.operations.OperationResultUiState
import com.mikeyphw.bkx.android.core.operations.OperationRuntimeState
import com.mikeyphw.bkx.android.core.operations.OperationStateStore
import com.mikeyphw.bkx.android.core.operations.bounded
import com.mikeyphw.bkx.android.core.operations.toOperationResultUiState
import com.mikeyphw.bkx.android.core.runs.RunArtifactSummary
import com.mikeyphw.bkx.android.core.runs.RunStatus
import com.mikeyphw.bkx.android.core.runs.RunSummary
import com.mikeyphw.bkx.android.core.runs.RunSummaryStore
import com.mikeyphw.bkx.android.core.runs.VerificationState
import com.mikeyphw.bkx.android.core.settings.ProgressPreference
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Process-scoped owner for embedded BKX execution.
 *
 * Long operations outlive individual Activities/ViewModels. Runtime state is
 * persisted and also published as a StateFlow so a recreated UI immediately
 * reconnects to truthful progress. The foreground service is refreshed from
 * the same authoritative state rather than maintaining a second progress view.
 */
class BkxOperationCoordinator(
    context: Context?,
    private val backendPort: BackendPort,
    private val runSummaryStore: RunSummaryStore,
    private val operationStateStore: OperationStateStore,
    private val requestBaseDirectoryPath: String,
    private val progressPreferenceProvider: () -> ProgressPreference = { ProgressPreference.EventDriven },
    private val taskerConditionPublisher: BkxTaskerConditionPublisher? = null,
) {
    private val appContext = context?.let { it.applicationContext ?: it }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMutex = Mutex()
    private val progressTracker = OperationProgressTracker()
    private val executions = ConcurrentHashMap<String, CompletableDeferred<OperationOutcome>>()
    private val _runtimeState = MutableStateFlow(OperationRuntimeState())
    val runtimeState: StateFlow<OperationRuntimeState> = _runtimeState.asStateFlow()

    init {
        scope.launch {
            runCatching { operationStateStore.read().bounded() }
                .onSuccess { _runtimeState.value = it }
        }
    }

    suspend fun snapshot(): OperationRuntimeState = stateMutex.withLock {
        operationStateStore.read().bounded().also { _runtimeState.value = it }
    }

    suspend fun execute(
        request: BridgeRequestEnvelope,
        recordInHistory: Boolean,
        manageForeground: Boolean = true,
    ): OperationOutcome {
        val candidate = CompletableDeferred<OperationOutcome>()
        val existing = executions.putIfAbsent(request.requestId, candidate)
        if (existing != null) return existing.await()

        scope.launch {
            val outcome = try {
                executeOwned(request, recordInHistory, manageForeground)
            } catch (error: Throwable) {
                val failure = OperationFailureMapper.fromProtocolException(
                    "BKX runtime failed before execution could establish a final result: ${error.message ?: error::class.java.simpleName}",
                )
                val failedState = try {
                    mutateState { state ->
                        val active = state.activeFor(request.requestId)
                        if (active == null) state else progressTracker.fail(
                            state,
                            request.requestId,
                            failure,
                            System.currentTimeMillis(),
                        )
                    }
                } catch (_: Throwable) {
                    // Persistence failure must never leave the caller suspended.
                    null
                }
                taskerConditionPublisher?.operationFailed(request, failedState?.activeFor(request.requestId), failure)
                OperationOutcome.Failed(failure)
            } finally {
                executions.remove(request.requestId, candidate)
            }
            candidate.complete(outcome)
        }
        return candidate.await()
    }

    suspend fun cancel(
        requestId: String,
        cancelRequestId: String,
        manageForeground: Boolean = true,
    ): OperationOutcome {
        val files = BridgeFileLayout.forRequest(requestBaseDirectoryPath, requestId)
        val state = mutateState { current ->
            val active = current.activeFor(requestId) ?: return@mutateState current
            if (active.status !in ACTIVE_STATUSES) return@mutateState current
            progressTracker.markCancelling(current, requestId, cancelRequestId, System.currentTimeMillis())
        }
        if (manageForeground) state.activeFor(requestId)?.let(::updateForeground)

        val submission = backendPort.cancel(requestId, files.cancelFilePath)
        if (!submission.accepted) {
            val failure = OperationFailureMapper.fromBackendReadiness(submission.readiness)
            val failed = mutateState { current ->
                progressTracker.fail(current, requestId, failure, System.currentTimeMillis())
            }
            if (manageForeground) failed.activeFor(requestId)?.let(::updateForeground)
            return OperationOutcome.Failed(failure)
        }
        return OperationOutcome.CancellationAccepted(requestId)
    }

    fun recoverInterruptedOperations() {
        scope.launch {
            val now = System.currentTimeMillis()
            val before = snapshot()
            val interrupted = before.activeOperations.values
                .filter { operation -> operation.status in ACTIVE_STATUSES && !executions.containsKey(operation.requestId) }
            if (interrupted.isEmpty()) return@launch
            val failure = OperationFailureMapper.interrupted(
                "BKX was interrupted before a final result was committed. Partial temporary artifacts were not promoted as successful output.",
            )
            mutateState { state ->
                var next = state
                interrupted.forEach { operation ->
                    next = progressTracker.interrupt(
                        state = next,
                        requestId = operation.requestId,
                        failure = failure,
                        nowEpochMillis = now,
                    )
                }
                next
            }
            interrupted.forEach { operation -> taskerConditionPublisher?.operationInterrupted(operation, failure) }
        }
    }

    private suspend fun executeOwned(
        request: BridgeRequestEnvelope,
        recordInHistory: Boolean,
        manageForeground: Boolean,
    ): OperationOutcome = executeOwnedConcurrent(request, recordInHistory, manageForeground)

    private suspend fun executeOwnedConcurrent(
        request: BridgeRequestEnvelope,
        recordInHistory: Boolean,
        manageForeground: Boolean,
    ): OperationOutcome {
        val files = BridgeFileLayout.forRequest(requestBaseDirectoryPath, request.requestId)
        val startedAt = System.currentTimeMillis()
        val startedState = mutateState { state ->
            progressTracker.trackStarted(
                state = state,
                requestId = request.requestId,
                operation = request.operation,
                startedAtEpochMillis = startedAt,
                cancelFilePath = files.cancelFilePath,
            )
        }
        startedState.activeFor(request.requestId)?.let { operation ->
            taskerConditionPublisher?.operationStarted(request, operation)
            if (manageForeground && request.operation in FOREGROUND_OPERATIONS) updateForeground(operation)
        }
        val progressPoller = startProgressPoller(request)
        var terminalNotification: Pair<ActiveOperation, OperationResultUiState>? = null

        return try {
            val submission = backendPort.submit(request, files)
            if (!submission.accepted) {
                val failure = OperationFailureMapper.fromBackendReadiness(submission.readiness)
                val failedState = mutateState { state -> progressTracker.fail(state, request.requestId, failure, System.currentTimeMillis()) }
                failedState.activeFor(request.requestId)?.let { operation ->
                    terminalNotification = operation to OperationResultUiState.Failed(failure)
                    taskerConditionPublisher?.operationFailed(request, operation, failure)
                } ?: taskerConditionPublisher?.operationFailed(request, null, failure)
                OperationOutcome.Failed(failure)
            } else {
                val rawResult = File(files.resultFilePath).readText()
                val result = backendPort.readFinalResult(rawResult)
                check(result.requestId == request.requestId) { "BKX result request id mismatch." }
                val completedAt = System.currentTimeMillis()
                val completedState = mutateState { state -> progressTracker.finish(state, result, completedAt) }
                completedState.activeFor(request.requestId)?.let { operation ->
                    terminalNotification = operation to result.toOperationResultUiState()
                    taskerConditionPublisher?.operationTerminal(request, operation, result)
                }
                if (recordInHistory) persistRun(request, startedAt, completedAt, result)
                OperationOutcome.Completed(result)
            }
        } catch (error: Throwable) {
            val failure = OperationFailureMapper.fromProtocolException(
                "BKX execution ended without a trustworthy final result: ${error.message ?: error::class.java.simpleName}",
            )
            val failedState = mutateState { state -> progressTracker.fail(state, request.requestId, failure, System.currentTimeMillis()) }
            failedState.activeFor(request.requestId)?.let { operation ->
                terminalNotification = operation to OperationResultUiState.Failed(failure)
                taskerConditionPublisher?.operationFailed(request, operation, failure)
            } ?: taskerConditionPublisher?.operationFailed(request, null, failure)
            OperationOutcome.Failed(failure)
        } finally {
            progressPoller.cancel()
            if (manageForeground && request.operation in FOREGROUND_OPERATIONS) {
                val context = appContext
                terminalNotification?.let { (operation, result) ->
                    if (context != null) runCatching { BkxOperationForegroundService.publishTerminal(context, operation, result) }
                }
                stopForegroundShellIfIdle(request.requestId)
            }
        }
    }

    private fun startProgressPoller(request: BridgeRequestEnvelope): Job = scope.launch {
        val requestId = request.requestId
        while (isActive && executions.containsKey(requestId)) {
            val progress = runCatching { backendPort.progress(requestId) }.getOrNull()
            if (progress != null) {
                val state = mutateState { current ->
                    val active = current.activeFor(requestId) ?: return@mutateState current
                    if (progress.seq <= active.lastSeq) return@mutateState current
                    progressTracker.consume(current, progress, System.currentTimeMillis())
                }
                state.activeFor(requestId)?.let { operation ->
                    taskerConditionPublisher?.operationProgress(request, operation)
                    updateForeground(operation)
                }
            }
            delay(progressPollIntervalMillis())
        }
    }

    private fun progressPollIntervalMillis(): Long = when (progressPreferenceProvider()) {
        ProgressPreference.EventDriven -> 250L
        ProgressPreference.Polling -> 1_000L
        ProgressPreference.Compact -> 2_000L
    }

    private suspend fun mutateState(transform: (OperationRuntimeState) -> OperationRuntimeState): OperationRuntimeState =
        stateMutex.withLock {
            val current = operationStateStore.read()
            val next = transform(current).bounded()
            operationStateStore.update(next)
            _runtimeState.value = next
            next
        }

    private suspend fun persistRun(request: BridgeRequestEnvelope, startedAt: Long, completedAt: Long, result: BridgeResultEnvelope) {
        val status = when (result.status) {
            BridgeResultStatus.Success -> RunStatus.Success
            BridgeResultStatus.Failure -> RunStatus.Failure
            BridgeResultStatus.Cancelled -> RunStatus.Cancelled
        }
        val uiResult = result.toOperationResultUiState()
        val verification = when {
            uiResult is OperationResultUiState.Failed && result.errors.any { it.code.contains("verify", true) } -> VerificationState.Failed
            result.artifacts.any { it.sha256 != null } -> VerificationState.Passed
            else -> VerificationState.NotRequested
        }
        runSummaryStore.upsert(
            RunSummary(
                requestId = result.requestId,
                operation = request.operation,
                jobId = request.payload["job_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                status = status,
                startedAtEpochMillis = startedAt,
                finishedAtEpochMillis = completedAt,
                durationMillis = (completedAt - startedAt).coerceAtLeast(0L),
                artifacts = result.artifacts.map { artifact ->
                    RunArtifactSummary(
                        label = artifact.label,
                        displayName = artifact.path.substringAfterLast('/').substringAfterLast('\\').ifBlank { artifact.label },
                        sha256 = artifact.sha256,
                    )
                },
                verification = verification,
                summary = result.summary.redactedSummary(),
            ),
        )
    }

    private fun updateForeground(operation: ActiveOperation) {
        if (operation.operation !in FOREGROUND_OPERATIONS || operation.status !in ACTIVE_STATUSES) return
        val context = appContext ?: return
        runCatching {
            ContextCompat.startForegroundService(
                context,
                BkxOperationForegroundService.intentFor(context, operation),
            )
        }
    }

    private suspend fun stopForegroundShellIfIdle(completedRequestId: String) {
        val state = snapshot()
        val anotherForegroundOperationIsActive = state.activeOperations.values.any { operation ->
            operation.requestId != completedRequestId &&
                operation.operation in FOREGROUND_OPERATIONS &&
                operation.status in ACTIVE_STATUSES
        }
        if (anotherForegroundOperationIsActive) return
        val context = appContext ?: return
        runCatching { context.stopService(Intent(context, BkxOperationForegroundService::class.java)) }
    }

    private fun String.redactedSummary(): String = lineSequence()
        .firstOrNull()
        .orEmpty()
        .replace(Regex("/data/(?:data|user/\\d+)/[^ ]+"), "private path")
        .replace(Regex("/storage/[^ ]+"), "storage path")
        .take(MAX_SUMMARY_CHARS)

    sealed interface OperationOutcome {
        data class Completed(val result: BridgeResultEnvelope) : OperationOutcome
        data class Failed(val failure: OperationFailureUiState) : OperationOutcome
        data class CancellationAccepted(val requestId: String) : OperationOutcome
    }

    private companion object {
        val ACTIVE_STATUSES = setOf(ActiveOperationStatus.Queued, ActiveOperationStatus.Running, ActiveOperationStatus.Cancelling)
        val FOREGROUND_OPERATIONS = setOf("backup.create", "backup.snapshot", "archive.verify", "restore.extract", "transfer.run")
        const val MAX_SUMMARY_CHARS = 500
    }
}
