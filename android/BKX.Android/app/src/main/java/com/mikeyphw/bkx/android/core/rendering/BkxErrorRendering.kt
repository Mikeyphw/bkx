package com.mikeyphw.bkx.android.core.rendering

import com.mikeyphw.bkx.android.core.operations.OperationFailureMapper
import com.mikeyphw.bkx.android.core.operations.OperationFailureUiState
import com.mikeyphw.bkx.android.core.operations.OperationResultUiState
import com.mikeyphw.bkx.android.core.operations.toOperationResultUiState

sealed interface BkxResultRenderingState {
    data object Empty : BkxResultRenderingState
    data class Success(
        val operation: String,
        val warnings: List<String>,
        val verificationStatus: BkxBridgeVerificationStatus,
        val actions: List<String>,
    ) : BkxResultRenderingState

    data class Failed(
        val operation: String,
        val failure: OperationFailureUiState,
    ) : BkxResultRenderingState

    data class Cancelled(
        val operation: String,
        val message: String,
        val actions: List<String>,
    ) : BkxResultRenderingState
}

object BkxResultRenderer {
    fun render(response: BkxBridgeResponseEnvelope): BkxResultRenderingState = when (val operation = response.toOperationResultUiState()) {
        is OperationResultUiState.Success -> BkxResultRenderingState.Success(
            operation = response.operation,
            warnings = response.warnings,
            verificationStatus = response.verification.status,
            actions = buildList {
                if (response.verification.status == BkxBridgeVerificationStatus.Failed) add("Review verification evidence")
                if (response.warnings.isNotEmpty()) add("Review warnings")
                add("View run details")
            },
        )
        is OperationResultUiState.Failed -> BkxResultRenderingState.Failed(response.operation, operation.failure)
        is OperationResultUiState.Cancelled -> BkxResultRenderingState.Cancelled(response.operation, operation.summary, operation.actions)
        OperationResultUiState.NotStarted -> BkxResultRenderingState.Empty
    }

    fun renderProtocolFailure(message: String): BkxResultRenderingState.Failed = BkxResultRenderingState.Failed(
        operation = "bridge.parse",
        failure = OperationFailureMapper.fromProtocolException(message),
    )
}
