package com.mikeyphw.bkx.android.root

import org.json.JSONObject

internal enum class ChrootctlRootMode { Query, Preview, Operation }

internal data class ChrootctlValidatedRequest(
    val requestId: String,
    val operation: String,
    val mode: ChrootctlRootMode,
)

/** Root-process duplicate of the allowlist: app-process validation is never trusted across Binder. */
internal object ChrootctlRootBridgePolicy {
    private data class Spec(val mode: ChrootctlRootMode, val fields: Set<String>)

    private val specs = mapOf(
        "chroots.list" to Spec(ChrootctlRootMode.Query, emptySet()),
        "chroots.get" to Spec(ChrootctlRootMode.Query, setOf("name")),
        "transfer.preview_export" to Spec(ChrootctlRootMode.Preview, setOf("archive", "name", "password")),
        "transfer.import_preview" to Spec(
            ChrootctlRootMode.Preview,
            setOf("allowLegacyMetadata", "archive", "conflict", "importStrategy", "name", "password", "path"),
        ),
        "transfer.export" to Spec(
            ChrootctlRootMode.Operation,
            setOf("archive", "name", "password", "previewId", "payloadHash", "resourceRevision"),
        ),
        "transfer.import" to Spec(
            ChrootctlRootMode.Operation,
            setOf(
                "allowLegacyMetadata", "archive", "conflict", "importStrategy", "name", "password", "path", "postImport",
                "previewId", "payloadHash", "resourceRevision",
            ),
        ),
    )

    fun validateRequest(raw: String, expectedMode: ChrootctlRootMode): Result<ChrootctlValidatedRequest> = runCatching {
        val bytes = raw.toByteArray(Charsets.UTF_8).size
        require(bytes <= MAX_BINDER_REQUEST_BYTES) { "Request exceeds Binder-safe ceiling." }
        val request = JSONObject(raw)
        val topKeys = request.keys().asSequence().toSet()
        val unknownTop = topKeys - setOf("protocolVersion", "requestId", "operation", "payload")
        require(unknownTop.isEmpty()) { "Unknown request field(s): ${unknownTop.sorted().joinToString()}" }
        require(request.optInt("protocolVersion", -1) == 1) { "Unsupported chrootctl bridge protocol." }
        val requestId = request.optString("requestId")
        require(validRequestId(requestId)) { "Invalid chrootctl request id." }
        val operation = request.optString("operation")
        val spec = requireNotNull(specs[operation]) { "Unsupported BKX chrootctl operation: $operation" }
        require(spec.mode == expectedMode) { "$operation is not a ${expectedMode.name.lowercase()} operation." }
        val payload = request.optJSONObject("payload") ?: JSONObject()
        val unknownPayload = payload.keys().asSequence().toSet() - spec.fields
        require(unknownPayload.isEmpty()) { "Unsupported payload field(s) for $operation: ${unknownPayload.sorted().joinToString()}" }
        ChrootctlValidatedRequest(requestId, operation, spec.mode)
    }

    fun validOperationId(value: String): Boolean = value.length in 1..128 && OPERATION_ID.matches(value)

    fun requestIdOrUnknown(raw: String?): String = runCatching { JSONObject(raw.orEmpty()).optString("requestId") }
        .getOrDefault("").takeIf(::validRequestId) ?: "unknown"

    private fun validRequestId(value: String): Boolean = value.length in 1..120 && REQUEST_ID.matches(value)

    private val REQUEST_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")
    private val OPERATION_ID = Regex("[A-Za-z0-9._:-]{1,128}")
    private const val MAX_BINDER_REQUEST_BYTES = 256 * 1024
}
