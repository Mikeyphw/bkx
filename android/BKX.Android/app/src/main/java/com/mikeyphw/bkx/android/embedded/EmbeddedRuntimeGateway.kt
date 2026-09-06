package com.mikeyphw.bkx.android.embedded

/** JSON-only runtime boundary shared by embedded Python and the generated Go runtime. */
interface EmbeddedRuntimeGateway {
    suspend fun readinessJson(): String
    suspend fun executeJson(requestJson: String): String
    suspend fun progressJson(requestId: String): String
    suspend fun cancelJson(requestId: String): String
}
