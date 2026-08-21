package com.mikeyphw.bkx.android.embedded

/** Small JSON-only boundary which keeps Chaquopy types out of the domain/backend layer. */
interface EmbeddedPythonGateway {
    fun readinessJson(): String
    fun executeJson(requestJson: String): String
    fun cancelJson(requestId: String): String
}
