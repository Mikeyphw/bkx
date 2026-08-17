package com.mikeyphw.bkx.android.termux

import com.mikeyphw.bkx.android.core.backend.BackendPort
import com.mikeyphw.bkx.android.core.backend.BackendReadiness
import com.mikeyphw.bkx.android.core.backend.BackendSubmission
import com.mikeyphw.bkx.android.core.bridge.BridgeJson
import com.mikeyphw.bkx.android.core.bridge.BridgeRequestEnvelope
import com.mikeyphw.bkx.android.core.bridge.BridgeRequestFiles
import com.mikeyphw.bkx.android.core.bridge.BridgeResultEnvelope

class TermuxBridgeAdapter(
    private val readinessProbe: TermuxReadinessProbe,
    private val dispatcher: TermuxRunCommandDispatcher,
    private val invocationFactory: TermuxRunCommandInvocationFactory,
) : BackendPort {
    override suspend fun checkReadiness(): BackendReadiness = readinessProbe.checkReadiness()

    override suspend fun submit(
        request: BridgeRequestEnvelope,
        files: BridgeRequestFiles,
    ): BackendSubmission {
        val requestJson = BridgeJson.encodeRequest(request)
        require(request.requestId == files.requestId) { "request id must match request file set" }

        val readiness = checkReadiness()
        if (readiness !is BackendReadiness.Ready && readiness !is BackendReadiness.AllowExternalAppsNotVerified) {
            return BackendSubmission(requestId = request.requestId, accepted = false, readiness = readiness)
        }

        return when (val result = dispatcher.dispatch(invocationFactory.create(files, requestJson))) {
            RunCommandDispatchResult.Started -> BackendSubmission(
                requestId = request.requestId,
                accepted = true,
                readiness = readiness,
            )
            is RunCommandDispatchResult.Failed -> BackendSubmission(
                requestId = request.requestId,
                accepted = false,
                readiness = BackendReadiness.ProbeFailed(result.reason),
            )
        }
    }

    override suspend fun cancel(requestId: String, cancelFilePath: String): BackendSubmission {
        val cancelJson = BridgeJson.encodeCancel(com.mikeyphw.bkx.android.core.bridge.BridgeCancelEnvelope(requestId = requestId))
        val readiness = checkReadiness()
        if (readiness !is BackendReadiness.Ready && readiness !is BackendReadiness.AllowExternalAppsNotVerified) {
            return BackendSubmission(requestId = requestId, accepted = false, readiness = readiness)
        }
        return when (val result = dispatcher.dispatch(invocationFactory.createCancel(requestId, cancelFilePath, cancelJson))) {
            RunCommandDispatchResult.Started -> BackendSubmission(
                requestId = requestId,
                accepted = true,
                readiness = readiness,
            )
            is RunCommandDispatchResult.Failed -> BackendSubmission(
                requestId = requestId,
                accepted = false,
                readiness = BackendReadiness.ProbeFailed(result.reason),
            )
        }
    }

    override suspend fun readFinalResult(rawResult: String): BridgeResultEnvelope =
        BridgeJson.parseFinalResult(rawResult)
}
