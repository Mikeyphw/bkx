package com.mikeyphw.bkx.android.core.bridge

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
object BridgeJson {
    const val SUPPORTED_PROTOCOL_VERSION = 1

    val json: Json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        encodeDefaults = true
        prettyPrint = false
    }

    fun encodeRequest(request: BridgeRequestEnvelope): String {
        requireSupported(request.protocolVersion)
        BridgeRequestIdPolicy.requireValid(request.requestId)
        require(request.operation.isNotBlank()) { "bridge operation must not be blank" }
        return json.encodeToString(request)
    }

    fun encodeCancel(cancel: BridgeCancelEnvelope): String {
        requireSupported(cancel.protocolVersion)
        BridgeRequestIdPolicy.requireValid(cancel.requestId)
        require(cancel.action == "cancel") { "bridge cancel action must be cancel" }
        return json.encodeToString(cancel)
    }

    fun parseFinalResult(raw: String, expectedRequestId: String? = null): BridgeResultEnvelope {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            throw BridgeProtocolException("Final backend result is empty.")
        }
        if (looksLikeMultipleJsonDocuments(trimmed)) {
            throw BridgeProtocolException("Final backend result must contain exactly one JSON document.")
        }
        val parsed = try {
            json.decodeFromString<BridgeResultEnvelope>(trimmed)
        } catch (exc: SerializationException) {
            throw BridgeProtocolException("Final backend result is not a valid bridge result JSON document.", exc)
        } catch (exc: IllegalArgumentException) {
            throw BridgeProtocolException("Final backend result is not a valid bridge result JSON document.", exc)
        }
        requireSupported(parsed.protocolVersion)
        BridgeRequestIdPolicy.requireValid(parsed.requestId)
        if (expectedRequestId != null && parsed.requestId != expectedRequestId) {
            throw BridgeProtocolException("Final backend result request id ${parsed.requestId} did not match expected $expectedRequestId.")
        }
        return parsed
    }

    fun parseProgress(raw: String, expectedRequestId: String? = null): BridgeProgressEnvelope? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "null") return null
        val parsed = try {
            json.decodeFromString<BridgeProgressEnvelope>(trimmed)
        } catch (exc: SerializationException) {
            throw BridgeProtocolException("Embedded progress is not valid bridge progress JSON.", exc)
        } catch (exc: IllegalArgumentException) {
            throw BridgeProtocolException("Embedded progress is not valid bridge progress JSON.", exc)
        }
        requireSupported(parsed.protocolVersion)
        BridgeRequestIdPolicy.requireValid(parsed.requestId)
        if (expectedRequestId != null && parsed.requestId != expectedRequestId) {
            throw BridgeProtocolException("Progress request id ${parsed.requestId} did not match expected $expectedRequestId.")
        }
        return parsed
    }

    fun requireSupported(protocolVersion: Int) {
        if (protocolVersion != SUPPORTED_PROTOCOL_VERSION) {
            throw BridgeProtocolException(
                "Unsupported bridge protocol version $protocolVersion; expected $SUPPORTED_PROTOCOL_VERSION.",
            )
        }
    }

    private fun looksLikeMultipleJsonDocuments(trimmed: String): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for ((index, char) in trimmed.withIndex()) {
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == '"') {
                    inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '{', '[' -> depth += 1
                '}', ']' -> {
                    depth -= 1
                    if (depth == 0 && index < trimmed.lastIndex) {
                        val tail = trimmed.substring(index + 1).trim()
                        if (tail.isNotEmpty()) return true
                    }
                }
            }
        }
        return false
    }
}

class BridgeProtocolException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
