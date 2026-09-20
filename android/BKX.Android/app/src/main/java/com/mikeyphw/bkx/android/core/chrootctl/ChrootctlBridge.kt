package com.mikeyphw.bkx.android.core.chrootctl

import com.mikeyphw.bkx.android.core.bridge.BridgeRequestIdPolicy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * BKX-facing model of the versioned `chrootctl app` machine bridge.
 *
 * Operation names are an enum and payload fields are checked locally before
 * privileged execution. The transport never accepts a shell fragment,
 * executable path, or arbitrary argv from app/UI callers.
 */
enum class ChrootctlBridgeMode {
    Query,
    Preview,
    Operation,
}

enum class ChrootctlOperation(
    val wireName: String,
    val mode: ChrootctlBridgeMode,
    internal val allowedPayloadFields: Set<String>,
) {
    ChrootsList("chroots.list", ChrootctlBridgeMode.Query, emptySet()),
    ChrootsGet("chroots.get", ChrootctlBridgeMode.Query, setOf("name")),
    PreviewExport("transfer.preview_export", ChrootctlBridgeMode.Preview, setOf("archive", "name", "password")),
    ImportPreview(
        "transfer.import_preview",
        ChrootctlBridgeMode.Preview,
        setOf("allowLegacyMetadata", "archive", "conflict", "importStrategy", "name", "password", "path"),
    ),
    Export(
        "transfer.export",
        ChrootctlBridgeMode.Operation,
        setOf("archive", "name", "password", "previewId", "payloadHash", "resourceRevision"),
    ),
    Import(
        "transfer.import",
        ChrootctlBridgeMode.Operation,
        setOf(
            "allowLegacyMetadata", "archive", "conflict", "importStrategy", "name", "password", "path", "postImport",
            "previewId", "payloadHash", "resourceRevision",
        ),
    ),
    ;

    companion object {
        fun fromWireName(value: String): ChrootctlOperation? = entries.firstOrNull { it.wireName == value }
    }
}

@Serializable
data class ChrootctlBridgeRequest(
    val protocolVersion: Int = ChrootctlBridgeCodec.PROTOCOL_VERSION,
    val requestId: String,
    val operation: String,
    val payload: JsonObject = buildJsonObject { },
) {
    init {
        require(protocolVersion == ChrootctlBridgeCodec.PROTOCOL_VERSION) { "Unsupported chrootctl bridge protocol $protocolVersion." }
        BridgeRequestIdPolicy.requireValid(requestId)
        val typed = requireNotNull(ChrootctlOperation.fromWireName(operation)) { "Unsupported BKX chrootctl operation: $operation" }
        val unknown = payload.keys - typed.allowedPayloadFields
        require(unknown.isEmpty()) { "Unsupported payload field(s) for $operation: ${unknown.sorted().joinToString()}" }
    }
}

@Serializable
data class ChrootctlBridgeError(
    val code: String,
    val message: String,
    val details: JsonElement = JsonObject(emptyMap()),
)

@Serializable
data class ChrootctlBridgeResponse(
    val protocolVersion: Int,
    val requestId: String,
    val ok: Boolean,
    val data: JsonElement? = null,
    val error: ChrootctlBridgeError? = null,
    val warnings: List<String> = emptyList(),
    val nextSteps: List<String> = emptyList(),
) {
    init {
        require(protocolVersion == ChrootctlBridgeCodec.PROTOCOL_VERSION) { "Unsupported chrootctl bridge protocol $protocolVersion." }
        require(requestId == "unknown" || requestId == "capabilities" || requestId == "state" || WIRE_ID.matches(requestId)) {
            "Invalid chrootctl bridge response request id."
        }
        require(ok || error != null) { "Failed chrootctl bridge response must contain a typed error." }
    }

    companion object {
        private val WIRE_ID = Regex("[A-Za-z0-9._:-]{1,128}")
    }
}

@Serializable
data class ChrootctlOperationEvent(
    val protocolVersion: Int = ChrootctlBridgeCodec.PROTOCOL_VERSION,
    val type: String,
    val operationId: String = "",
    val requestId: String,
    val timestamp: Double? = null,
    val sequence: Long? = null,
    val payload: JsonObject = buildJsonObject { },
    val data: JsonElement? = null,
    val error: ChrootctlBridgeError? = null,
    val warnings: List<String> = emptyList(),
    val nextSteps: List<String> = emptyList(),
) {
    val terminal: Boolean
        get() = type in TERMINAL_EVENT_TYPES

    init {
        require(protocolVersion == ChrootctlBridgeCodec.PROTOCOL_VERSION) { "Unsupported chrootctl event protocol $protocolVersion." }
        BridgeRequestIdPolicy.requireValid(requestId)
        require(EVENT_TYPE.matches(type)) { "Invalid chrootctl event type: $type" }
        require(operationId.isEmpty() || OPERATION_ID.matches(operationId)) { "Invalid chrootctl operation id." }
        require(sequence == null || sequence >= 0L) { "chrootctl event sequence must be non-negative" }
    }

    companion object {
        private val EVENT_TYPE = Regex("[a-z][a-z0-9_.-]{1,80}")
        private val OPERATION_ID = Regex("[A-Za-z0-9._:-]{1,128}")
        val TERMINAL_EVENT_TYPES: Set<String> = setOf(
            "operation_succeeded",
            "operation_failed",
            "operation_cancelled",
            "operation_blocked",
            "bridge_process_failed",
            "bridge_stream_interrupted",
            "bridge_error",
        )
    }
}


@Serializable
data class ChrootctlPreviewContract(
    val previewId: String,
    val payloadHash: String,
    val resourceRevision: String,
    val expiresAt: Double? = null,
    val actionOperation: String? = null,
)

@Serializable
data class ChrootctlChrootRecord(
    val name: String,
    val distro: String,
    val path: String,
    val arch: String = "",
    @SerialName("release_name") val releaseName: String = "",
    val version: String = "",
    val phase: String = "",
    val installed: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    val metadata: Map<String, String> = emptyMap(),
)

class ChrootctlBridgeProtocolException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

object ChrootctlBridgeCodec {
    const val PROTOCOL_VERSION = 1
    const val MAX_BINDER_REQUEST_BYTES = 256 * 1024
    const val MAX_RESPONSE_BYTES = 1024 * 1024
    const val MAX_EVENT_BYTES = 256 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun request(requestId: String, operation: ChrootctlOperation, payload: JsonObject = buildJsonObject { }): ChrootctlBridgeRequest =
        ChrootctlBridgeRequest(
            requestId = BridgeRequestIdPolicy.normalize(requestId),
            operation = operation.wireName,
            payload = payload,
        )

    fun encodeRequest(request: ChrootctlBridgeRequest): String = json.encodeToString(request).also { encoded ->
        val bytes = encoded.toByteArray(Charsets.UTF_8).size
        require(bytes <= MAX_BINDER_REQUEST_BYTES) {
            "chrootctl request is $bytes bytes; Binder-safe ceiling is $MAX_BINDER_REQUEST_BYTES bytes."
        }
    }

    fun decodeResponse(raw: String, expectedRequestId: String? = null): ChrootctlBridgeResponse {
        val trimmed = raw.trim()
        requireBounded(trimmed, MAX_RESPONSE_BYTES, "chrootctl response")
        if (trimmed.isBlank()) throw ChrootctlBridgeProtocolException("chrootctl bridge returned an empty response.")
        val response = try {
            json.decodeFromString<ChrootctlBridgeResponse>(trimmed)
        } catch (error: SerializationException) {
            throw ChrootctlBridgeProtocolException("chrootctl bridge returned malformed response JSON.", error)
        } catch (error: IllegalArgumentException) {
            throw ChrootctlBridgeProtocolException("chrootctl bridge returned an invalid response envelope.", error)
        }
        if (expectedRequestId != null && response.requestId != expectedRequestId) {
            throw ChrootctlBridgeProtocolException(
                "chrootctl response request id ${response.requestId} did not match $expectedRequestId.",
            )
        }
        return response
    }

    fun decodeEvent(raw: String, expectedRequestId: String): ChrootctlOperationEvent {
        requireBounded(raw, MAX_EVENT_BYTES, "chrootctl NDJSON event")
        val trimmed = raw.trim()
        val objectValue = try {
            json.parseToJsonElement(trimmed) as? JsonObject
                ?: throw ChrootctlBridgeProtocolException("chrootctl NDJSON event must be a JSON object.")
        } catch (error: ChrootctlBridgeProtocolException) {
            throw error
        } catch (error: Throwable) {
            throw ChrootctlBridgeProtocolException("chrootctl emitted malformed NDJSON event JSON.", error)
        }
        val event = if ("type" !in objectValue && "ok" in objectValue) {
            val response = decodeResponse(trimmed, expectedRequestId)
            if (response.ok) throw ChrootctlBridgeProtocolException("chrootctl run emitted a response envelope without an event type.")
            ChrootctlOperationEvent(
                type = "bridge_error",
                operationId = "",
                requestId = response.requestId,
                error = response.error,
                warnings = response.warnings,
                nextSteps = response.nextSteps,
            )
        } else {
            try {
                json.decodeFromString<ChrootctlOperationEvent>(trimmed)
            } catch (error: SerializationException) {
                throw ChrootctlBridgeProtocolException("chrootctl emitted malformed NDJSON event JSON.", error)
            } catch (error: IllegalArgumentException) {
                throw ChrootctlBridgeProtocolException("chrootctl emitted an invalid operation event.", error)
            }
        }
        if (event.requestId != expectedRequestId) {
            throw ChrootctlBridgeProtocolException(
                "chrootctl event request id ${event.requestId} did not match $expectedRequestId.",
            )
        }
        return event
    }

    fun chroots(response: ChrootctlBridgeResponse): List<ChrootctlChrootRecord> {
        require(response.ok) { response.error?.message ?: "chroots.list failed" }
        val data = response.data
        if (data == null || data is JsonNull) return emptyList()
        val rows = data as? JsonArray ?: throw ChrootctlBridgeProtocolException("chroots.list response data must be an array.")
        return rows.mapIndexed { index, value ->
            try {
                json.decodeFromJsonElement(ChrootctlChrootRecord.serializer(), value)
            } catch (error: Throwable) {
                throw ChrootctlBridgeProtocolException("chroots.list item $index is invalid.", error)
            }
        }
    }

    fun chroot(response: ChrootctlBridgeResponse): ChrootctlChrootRecord {
        require(response.ok) { response.error?.message ?: "chroots.get failed" }
        val data = response.data ?: throw ChrootctlBridgeProtocolException("chroots.get response has no data.")
        return try {
            json.decodeFromJsonElement(ChrootctlChrootRecord.serializer(), data)
        } catch (error: Throwable) {
            throw ChrootctlBridgeProtocolException("chroots.get response data is invalid.", error)
        }
    }

    fun previewContract(response: ChrootctlBridgeResponse): ChrootctlPreviewContract {
        require(response.ok) { response.error?.message ?: "chrootctl preview failed" }
        val data = response.data as? JsonObject
            ?: throw ChrootctlBridgeProtocolException("chrootctl preview response data must be an object.")
        fun required(name: String): String = data[name]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            ?: throw ChrootctlBridgeProtocolException("chrootctl preview is missing $name.")
        return ChrootctlPreviewContract(
            previewId = required("previewId"),
            payloadHash = required("payloadHash"),
            resourceRevision = required("resourceRevision"),
            expiresAt = data["expiresAt"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
            actionOperation = data["actionOperation"]?.jsonPrimitive?.contentOrNull,
        )
    }

    fun bindPreview(payload: JsonObject, contract: ChrootctlPreviewContract): JsonObject = buildJsonObject {
        payload.forEach { (key, value) -> put(key, value) }
        put("previewId", contract.previewId)
        put("payloadHash", contract.payloadHash)
        put("resourceRevision", contract.resourceRevision)
    }

    fun chrootNamePayload(name: String): JsonObject = buildJsonObject {
        put("name", requireNonBlank(name, "chroot name"))
    }

    fun exportPayload(name: String, archive: String? = null, password: String? = null): JsonObject = buildJsonObject {
        put("name", requireNonBlank(name, "chroot name"))
        archive?.takeIf(String::isNotBlank)?.let { put("archive", it) }
        password?.let { put("password", it) }
    }

    private fun requireNonBlank(value: String, label: String): String = value.trim().also {
        require(it.isNotEmpty()) { "$label must not be blank" }
        require(it.length <= 256) { "$label is too long" }
    }

    private fun requireBounded(raw: String, maxBytes: Int, label: String) {
        val bytes = raw.toByteArray(Charsets.UTF_8).size
        if (bytes > maxBytes) throw ChrootctlBridgeProtocolException("$label exceeds $maxBytes bytes.")
    }
}

data class ChrootctlRunResult(
    val start: ChrootctlBridgeResponse,
    val operationId: String?,
    val terminalEvent: ChrootctlOperationEvent?,
) {
    val terminal: Boolean get() = terminalEvent?.terminal == true
    val succeeded: Boolean get() = terminalEvent?.type == "operation_succeeded"
}

interface ChrootctlBridgePort {
    suspend fun query(request: ChrootctlBridgeRequest): ChrootctlBridgeResponse
    suspend fun preview(request: ChrootctlBridgeRequest): ChrootctlBridgeResponse
    suspend fun run(
        request: ChrootctlBridgeRequest,
        onEvent: suspend (ChrootctlOperationEvent) -> Unit,
    ): ChrootctlRunResult
    suspend fun operation(operationId: String): ChrootctlBridgeResponse
    suspend fun cancel(operationId: String): ChrootctlBridgeResponse
    suspend fun reconcile(operationId: String): ChrootctlBridgeResponse

    suspend fun listChroots(requestId: String): List<ChrootctlChrootRecord> = chrootListResponse(requestId).let(ChrootctlBridgeCodec::chroots)

    suspend fun getChroot(requestId: String, name: String): ChrootctlChrootRecord = query(
        ChrootctlBridgeCodec.request(
            requestId = requestId,
            operation = ChrootctlOperation.ChrootsGet,
            payload = ChrootctlBridgeCodec.chrootNamePayload(name),
        ),
    ).let(ChrootctlBridgeCodec::chroot)

    private suspend fun chrootListResponse(requestId: String): ChrootctlBridgeResponse = query(
        ChrootctlBridgeCodec.request(requestId, ChrootctlOperation.ChrootsList),
    )
}
