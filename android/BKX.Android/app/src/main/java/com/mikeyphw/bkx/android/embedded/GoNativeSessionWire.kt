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
        require(id.isNotBlank()) { "Go session command id must not be blank." }
        require(entrypoint in ENTRYPOINTS) { "Unsupported Go session entrypoint: $entrypoint" }
        return buildJsonObject {
            put("id", id)
            put("entrypoint", entrypoint)
            if (!requestId.isNullOrBlank()) put("request_id", requestId)
            if (requestJson != null) put("request", json.parseToJsonElement(requestJson))
        }.toString()
    }

    fun reply(raw: String, expectedId: String): JsonElement {
        val root = json.parseToJsonElement(raw).jsonObject
        require(root.keys == setOf("id", "value")) { "Go session reply has unexpected fields." }
        require(root.getValue("id").jsonPrimitive.content == expectedId) { "Go session reply id mismatch." }
        val value = root["value"] ?: JsonNull
        if (value is kotlinx.serialization.json.JsonObject && "session_error" in value) {
            throw IllegalStateException(value.getValue("session_error").jsonPrimitive.content)
        }
        return value
    }

    private val ENTRYPOINTS = setOf("readiness", "execute", "progress", "cancel")
}
