package com.mikeyphw.bkx.android.core.backend

import com.mikeyphw.bkx.android.core.bridge.BridgeJson
import com.mikeyphw.bkx.android.core.bridge.BridgeProgressEnvelope
import com.mikeyphw.bkx.android.core.bridge.BridgeRequestEnvelope
import com.mikeyphw.bkx.android.core.bridge.BridgeRequestFiles
import com.mikeyphw.bkx.android.core.bridge.BridgeResultEnvelope

class FakeBackendPort(
    private var readiness: BackendReadiness = BackendReadiness.Ready(
        backendVersion = "fake-bkx",
        bridgeProtocolVersion = BridgeJson.SUPPORTED_PROTOCOL_VERSION,
        capabilities = setOf(
            "readiness.probe",
            "backup.plan",
            "backup.create",
            "archive.compression.none",
            "archive.compression.gzip",
            "archive.compression.zstd",
            "archive.split",
            "archive.list",
            "archive.info",
            "archive.browse",
            "archive.verify",
            "restore.preview",
            "restore.extract",
            "transfer.plan",
            "transfer.run",
            "runs.list",
            "runs.show",
            "run.cancel",
        ),
    ),
) : BackendPort {
    val submittedRequests = mutableListOf<Pair<BridgeRequestEnvelope, BridgeRequestFiles>>()
    val cancellations = mutableListOf<Pair<String, String>>()

    fun setReadiness(next: BackendReadiness) {
        readiness = next
    }

    override suspend fun checkReadiness(): BackendReadiness = readiness

    override suspend fun submit(
        request: BridgeRequestEnvelope,
        files: BridgeRequestFiles,
    ): BackendSubmission {
        submittedRequests += request to files
        return BackendSubmission(
            requestId = request.requestId,
            accepted = readiness is BackendReadiness.Ready,
            readiness = readiness,
        )
    }

    override suspend fun progress(requestId: String): BridgeProgressEnvelope? = null

    override suspend fun cancel(requestId: String, cancelFilePath: String): BackendSubmission {
        cancellations += requestId to cancelFilePath
        return BackendSubmission(
            requestId = requestId,
            accepted = readiness is BackendReadiness.Ready,
            readiness = readiness,
        )
    }

    override suspend fun readFinalResult(rawResult: String): BridgeResultEnvelope =
        BridgeJson.parseFinalResult(rawResult)
}
