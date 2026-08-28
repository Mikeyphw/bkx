package com.mikeyphw.bkx.android.app.runtime

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.mikeyphw.bkx.android.app.service.BkxOperationForegroundService
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
) {
    private val appContext = context?.let { it.applicationContext ?: it }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMutex = Mutex()
    private val foregroundOperationMutex = Mutex()
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
    ): OperationOutcome {
        val candidate = CompletableDeferred<OperationOutcome>()
        val existing = executions.putIfAbsent(request.requestId, candidate)
        if (existing != null) return existing.await()

        scope.launch {
            val outcome = try {
                executeOwned(request, recordInHistory)
            } catch (error: Throwable) {
                val failure = OperationFailureMapper.fromProtocolException(
                    "BKX runtime failed before execution could establish a final result: ${error.message ?: error::class.java.simpleName}",
                )
                try {
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
                }
                OperationOutcome.Failed(failure)
            } finally {
                executions.remove(request.requestId, candidate)
            }
            candidate.complete(outcome)
        }
        return candidate.await()
    }

    suspend fun cancel(requestId: String, cancelRequestId: String): OperationOutcome {
        val files = BridgeFileLayout.forRequest(requestBaseDirectoryPath, requestId)
        val state = mutateState { current ->
            val active = current.activeFor(requestId) ?: return@mutateState current
            if (active.status !in ACTIVE_STATUSES) return@mutateState current
            progressTracker.markCancelling(current, requestId, cancelRequestId, System.currentTimeMillis())
        }
        state.activeFor(requestId)?.let(::updateForeground)

        val submission = backendPort.cancel(requestId, files.cancelFilePath)
        if (!submission.accepted) {
            val failure = OperationFailureMapper.fromBackendReadiness(submission.readiness)
            val failed = mutateState { current ->
                progressTracker.fail(current, requestId, failure, System.currentTimeMillis())
            }
            failed.activeFor(requestId)?.let(::updateForeground)
            return OperationOutcome.Failed(failure)
        }
        return OperationOutcome.CancellationAccepted(requestId)
    }

    fun recoverInterruptedOperations() {
        scope.launch {
            val now = System.currentTimeMillis()
            mutateState { state ->
                var next = state
                state.activeOperations.values
                    .filter { operation -> operation.status in ACTIVE_STATUSES && !executions.containsKey(operation.requestId) }
                    .forEach { operation ->
                        next = progressTracker.interrupt(
                            state = next,
                            requestId = operation.requestId,
                            failure = OperationFailureMapper.interrupted(
                                "BKX was interrupted before a final result was committed. Partial temporary artifacts were not promoted as successful output.",
                            ),
                            nowEpochMillis = now,
                        )
                    }
                next
            }
        }
    }

    private suspend fun executeOwned(
        request: BridgeRequestEnvelope,
        recordInHistory: Boolean,
    ): OperationOutcome = if (request.operation in FOREGROUND_OPERATIONS) {
        foregroundOperationMutex.withLock { executeOwnedSerialized(request, recordInHistory) }
    } else {
        executeOwnedSerialized(request, recordInHistory)
    }

    private suspend fun executeOwnedSerialized(
        request: BridgeRequestEnvelope,
        recordInHistory: Boolean,
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
        if (request.operation in FOREGROUND_OPERATIONS) {
            startedState.activeFor(request.requestId)?.let(::updateForeground)
        }
        val progressPoller = startProgressPoller(request.requestId)

        return try {
            val submission = backendPort.submit(request, files)
            if (!submission.accepted) {
                val failure = OperationFailureMapper.fromBackendReadiness(submission.readiness)
                mutateState { state -> progressTracker.fail(state, request.requestId, failure, System.currentTimeMillis()) }
                OperationOutcome.Failed(failure)
            } else {
                val rawResult = File(files.resultFilePath).readText()
                val result = backendPort.readFinalResult(rawResult)
                check(result.requestId == request.requestId) { "BKX result request id mismatch." }
                val completedAt = System.currentTimeMillis()
                mutateState { state -> progressTracker.finish(state, result, completedAt) }
                if (recordInHistory) persistRun(request.operation, startedAt, completedAt, result)
                OperationOutcome.Completed(result)
            }
        } catch (error: Throwable) {
            val failure = OperationFailureMapper.fromProtocolException(
                "BKX execution ended without a trustworthy final result: ${error.message ?: error::class.java.simpleName}",
            )
            mutateState { state -> progressTracker.fail(state, request.requestId, failure, System.currentTimeMillis()) }
            OperationOutcome.Failed(failure)
        } finally {
            progressPoller.cancel()
            if (request.operation in FOREGROUND_OPERATIONS) stopForegroundShell()
        }
    }

    private fun startProgressPoller(requestId: String): Job = scope.launch {
        while (isActive && executions.containsKey(requestId)) {
            val progress = runCatching { backendPort.progress(requestId) }.getOrNull()
            if (progress != null) {
                val state = mutateState { current ->
                    val active = current.activeFor(requestId) ?: return@mutateState current
                    if (progress.seq <= active.lastSeq) return@mutateState current
                    progressTracker.consume(current, progress, System.currentTimeMillis())
                }
                state.activeFor(requestId)?.let(::updateForeground)
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

    private suspend fun persistRun(operation: String, startedAt: Long, completedAt: Long, result: BridgeResultEnvelope) {
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
                operation = operation,
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

    private fun stopForegroundShell() {
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
        val FOREGROUND_OPERATIONS = setOf("backup.create", "archive.verify", "restore.extract", "transfer.run")
        const val MAX_SUMMARY_CHARS = 500
    }
}
