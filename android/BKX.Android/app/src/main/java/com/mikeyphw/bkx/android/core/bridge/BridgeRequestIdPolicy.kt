package com.mikeyphw.bkx.android.core.bridge

/**
 * Single Android-side request-id grammar used anywhere a bridge request id
 * becomes a filename, runtime map key, cancel target, reconnect token or
 * notification request code.
 */
object BridgeRequestIdPolicy {
    const val MAX_LENGTH = 120
    private val SAFE_CHARS = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")

    fun normalize(raw: String): String = raw.trim().also(::requireValid)

    fun isValid(raw: String): Boolean = runCatching { normalize(raw); true }.getOrDefault(false)

    fun requireValid(requestId: String) {
        require(requestId.isNotBlank()) { "request_id must not be blank" }
        require(requestId.length <= MAX_LENGTH) { "request_id must be at most $MAX_LENGTH characters" }
        require(requestId != "." && requestId != "..") { "request_id must not be a relative path segment" }
        require(!requestId.contains(".." + '/') && !requestId.contains('/' + "..")) { "request_id must not contain traversal segments" }
        require(SAFE_CHARS.matches(requestId)) { "request_id must be file-name safe" }
    }
}
