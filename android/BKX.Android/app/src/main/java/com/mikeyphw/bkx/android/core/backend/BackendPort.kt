package com.mikeyphw.bkx.android.core.backend

import com.mikeyphw.bkx.android.core.bridge.BridgeProgressEnvelope
import com.mikeyphw.bkx.android.core.bridge.BridgeRequestEnvelope
import com.mikeyphw.bkx.android.core.bridge.BridgeRequestFiles
import com.mikeyphw.bkx.android.core.bridge.BridgeResultEnvelope

interface BackendPort {
    suspend fun checkReadiness(): BackendReadiness

    suspend fun submit(
        request: BridgeRequestEnvelope,
        files: BridgeRequestFiles,
    ): BackendSubmission

    suspend fun progress(requestId: String): BridgeProgressEnvelope?

    suspend fun cancel(requestId: String, cancelFilePath: String): BackendSubmission

    suspend fun readFinalResult(rawResult: String): BridgeResultEnvelope
}

data class BackendSubmission(
    val requestId: String,
    val accepted: Boolean,
    val readiness: BackendReadiness,
)
