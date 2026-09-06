package com.mikeyphw.bkx.android.embedded

/** JSON-only protocol-v1 boundary for the packaged Go runtime and privileged proxy. */
interface EmbeddedRuntimeGateway {
    suspend fun readinessJson(): String
    suspend fun executeJson(requestJson: String): String
    suspend fun progressJson(requestId: String): String
    suspend fun cancelJson(requestId: String): String
}


/** Runtime boundary with an explicit packaged-runtime availability probe. */
interface AvailableEmbeddedRuntimeGateway : EmbeddedRuntimeGateway {
    fun isAvailable(): Boolean
}
