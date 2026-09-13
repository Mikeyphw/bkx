package com.mikeyphw.bkx.android.embedded

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object GoNativeSessionWire {
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        encodeDefaults = true
    }

    fun command(
        id: String,
        entrypoint: String,
        requestId: String? = null,
        requestJson: String? = null,
    ): String {
        require(id.isNotBlank() && id.none { it.isWhitespace() }) { "Go session command id must be non-blank and single-token." }
        require(entrypoint in ENTRYPOINTS) { "Unsupported Go session entrypoint: $entrypoint" }
        if (requestJson != null) {
            require(requestJson.toByteArray(Charsets.UTF_8).size <= MAX_COMMAND_BYTES) {
                "Go session request exceeds ${MAX_COMMAND_BYTES} byte bridge limit."
            }
        }
        val encoded = buildJsonObject {
            put("id", id)
            put("entrypoint", entrypoint)
            if (!requestId.isNullOrBlank()) put("request_id", requestId)
            if (requestJson != null) put("request", json.parseToJsonElement(requestJson))
        }.toString()
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_COMMAND_BYTES) {
            "Go session command exceeds ${MAX_COMMAND_BYTES} byte bridge limit."
        }
        return encoded
    }

    fun reply(raw: String, expectedId: String): JsonElement {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_REPLY_BYTES) {
            "Go session reply exceeds ${MAX_REPLY_BYTES} byte bridge limit."
        }
        val root = json.parseToJsonElement(raw).jsonObject
        require("id" in root && "value" in root) { "Go session reply must contain id and value." }
        require(root.getValue("id").jsonPrimitive.content == expectedId) { "Go session reply id mismatch." }
        val value = root["value"] ?: JsonNull
        if (value is kotlinx.serialization.json.JsonObject && "session_error" in value) {
            throw IllegalStateException(value.getValue("session_error").jsonPrimitive.content)
        }
        return value
    }

    const val MAX_COMMAND_BYTES = 4 * 1024 * 1024
    const val MAX_REPLY_BYTES = 4 * 1024 * 1024

    private val ENTRYPOINTS = setOf("readiness", "execute", "progress", "cancel")
}
