package com.mikeyphw.bkx.android.embedded

/** Small JSON-only boundary which keeps Chaquopy and root-service types out of the backend layer. */
interface EmbeddedPythonGateway {
    suspend fun readinessJson(): String
    suspend fun executeJson(requestJson: String): String
    suspend fun cancelJson(requestId: String): String
}
