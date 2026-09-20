package com.mikeyphw.bkx.android.root

import android.content.Context
import org.json.JSONObject

/**
 * Small durable request -> operation ledger for the external chrootctl bridge.
 *
 * chrootctl remains authoritative for operation state. BKX persists only the
 * identifiers needed to reconcile an interrupted stream after process/UI loss;
 * no payloads, passwords, archive paths, or other request data are stored here.
 */
internal class ChrootctlOperationLedger(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun recordStarted(requestId: String, operationId: String) {
        if (!validRequestId(requestId) || !validOperationId(operationId)) return
        prefs.edit().putString(key(requestId), JSONObject()
            .put("operationId", operationId)
            .put("terminal", false)
            .toString()).apply()
    }

    fun recordTerminal(requestId: String, operationId: String) {
        if (!validRequestId(requestId) || !validOperationId(operationId)) return
        prefs.edit().putString(key(requestId), JSONObject()
            .put("operationId", operationId)
            .put("terminal", true)
            .toString()).apply()
    }

    fun operationId(requestId: String): String? {
        if (!validRequestId(requestId)) return null
        val raw = prefs.getString(key(requestId), null) ?: return null
        return runCatching { JSONObject(raw).optString("operationId") }
            .getOrNull()?.takeIf(::validOperationId)
    }

    fun clear(requestId: String) {
        if (validRequestId(requestId)) prefs.edit().remove(key(requestId)).apply()
    }

    private fun key(requestId: String) = "request.$requestId"

    private fun validRequestId(value: String) = value.length in 1..120 && REQUEST_ID.matches(value)
    private fun validOperationId(value: String) = value.length in 1..128 && OPERATION_ID.matches(value)

    private companion object {
        const val PREFS = "bkx_chrootctl_operation_ledger_v1"
        val REQUEST_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")
        val OPERATION_ID = Regex("[A-Za-z0-9._:-]{1,128}")
    }
}
