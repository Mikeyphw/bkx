package com.mikeyphw.bkx.android.core.operations

import com.mikeyphw.bkx.android.core.bridge.BridgeProgressEnvelope
import com.mikeyphw.bkx.android.core.bridge.BridgeResultEnvelope

class OperationProgressTracker(
    private val maxEventsPerRequest: Int = 200,
) {
    init {
        require(maxEventsPerRequest > 0) { "maxEventsPerRequest must be positive" }
    }

    fun trackStarted(
        state: OperationRuntimeState,
        requestId: String,
        operation: String,
        startedAtEpochMillis: Long,
        cancelFilePath: String? = null,
    ): OperationRuntimeState {
        val active = ActiveOperation(
            requestId = requestId,
            operation = operation,
            status = ActiveOperationStatus.Running,
            startedAtEpochMillis = startedAtEpochMillis,
            lastSeenEpochMillis = startedAtEpochMillis,
            lastPhase = "started",
            lastMessage = "BKX accepted the request.",
            cancelFilePath = cancelFilePath,
        )
        return state.copy(activeOperations = state.activeOperations + (requestId to active))
    }

    fun consume(
        state: OperationRuntimeState,
        envelope: BridgeProgressEnvelope,
        observedAtEpochMillis: Long,
    ): OperationRuntimeState = consume(
        state = state,
        event = OperationProgressEvent.fromBridgeProgress(envelope, observedAtEpochMillis),
    )

    fun consume(
        state: OperationRuntimeState,
        event: OperationProgressEvent,
    ): OperationRuntimeState {
        val active = state.activeOperations[event.requestId]
            ?: throw IllegalArgumentException("No active operation exists for progress request id ${event.requestId}.")
        val nextActive = active.withProgress(event)
        val timeline = (state.progressEvents[event.requestId].orEmpty() + event).takeLast(maxEventsPerRequest)
        return state.copy(
            activeOperations = state.activeOperations + (event.requestId to nextActive),
            progressEvents = state.progressEvents + (event.requestId to timeline),
        )
    }

    fun markCancelling(
        state: OperationRuntimeState,
        requestId: String,
        cancelRequestId: String,
        nowEpochMillis: Long,
    ): OperationRuntimeState {
        val active = state.activeOperations[requestId]
            ?: throw IllegalArgumentException("No active operation exists for cancel request id $requestId.")
        return state.copy(activeOperations = state.activeOperations + (requestId to active.asCancelling(cancelRequestId, nowEpochMillis)))
    }

    fun finish(
        state: OperationRuntimeState,
        result: BridgeResultEnvelope,
        nowEpochMillis: Long,
    ): OperationRuntimeState {
        val active = state.activeOperations[result.requestId]
            ?: throw IllegalArgumentException("No active operation exists for result request id ${result.requestId}.")
        val resultState = result.toOperationResultUiState()
        val nextStatus = when (resultState) {
            is OperationResultUiState.Success -> ActiveOperationStatus.Success
            is OperationResultUiState.Cancelled -> ActiveOperationStatus.Cancelled
            is OperationResultUiState.Failed -> ActiveOperationStatus.Failure
            OperationResultUiState.NotStarted -> ActiveOperationStatus.Unknown
        }
        val nextActive = active.finished(nextStatus, result.summary.ifBlank { nextStatus.name }, nowEpochMillis)
        val failureStates = if (resultState is OperationResultUiState.Failed) {
            state.failureStates + (result.requestId to resultState.failure)
        } else {
            state.failureStates - result.requestId
        }
        return state.copy(
            activeOperations = state.activeOperations + (result.requestId to nextActive),
            failureStates = failureStates,
        )
    }

    fun fail(
        state: OperationRuntimeState,
        requestId: String,
        failure: OperationFailureUiState,
        nowEpochMillis: Long,
    ): OperationRuntimeState {
        val active = state.activeOperations[requestId]
            ?: ActiveOperation(
                requestId = requestId,
                operation = "unknown",
                status = ActiveOperationStatus.Unknown,
                startedAtEpochMillis = nowEpochMillis,
            )
        return state.copy(
            activeOperations = state.activeOperations + (
                requestId to active.finished(ActiveOperationStatus.Failure, failure.message, nowEpochMillis)
            ),
            failureStates = state.failureStates + (requestId to failure),
        )
    }

    fun reconnect(snapshot: OperationReconnectSnapshot): OperationRuntimeState = OperationRuntimeState(
        activeOperations = snapshot.activeOperations.associateBy { it.requestId },
        progressEvents = emptyMap(),
        failureStates = emptyMap(),
    )
}
