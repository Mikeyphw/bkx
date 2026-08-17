package com.mikeyphw.bkx.android.core.rendering

import com.mikeyphw.bkx.android.core.bridge.BridgeJson
import com.mikeyphw.bkx.android.core.bridge.BridgeProtocolException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString

object BkxBridgeResponseParser {
    fun parseStrictFinalResponse(raw: String): BkxBridgeResponseEnvelope {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            throw BridgeProtocolException("Final BKX response is empty.")
        }
        if (hasMoreThanOneJsonDocument(trimmed)) {
            throw BridgeProtocolException("Final BKX response must contain exactly one JSON document.")
        }
        val parsed = try {
            BridgeJson.json.decodeFromString<BkxBridgeResponseEnvelope>(trimmed)
        } catch (exc: SerializationException) {
            throw BridgeProtocolException("Final BKX response is malformed or partial JSON.", exc)
        } catch (exc: IllegalArgumentException) {
            throw BridgeProtocolException("Final BKX response is malformed or partial JSON.", exc)
        }
        BridgeJson.requireSupported(parsed.protocolVersion)
        return parsed
    }

    private fun hasMoreThanOneJsonDocument(trimmed: String): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        var sawClosedTopLevel = false
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
                '{', '[' -> {
                    if (sawClosedTopLevel && !char.isWhitespace()) return true
                    depth += 1
                }
                '}', ']' -> {
                    depth -= 1
                    if (depth < 0) return false
                    if (depth == 0) {
                        sawClosedTopLevel = true
                        if (index < trimmed.lastIndex && trimmed.substring(index + 1).trim().isNotEmpty()) {
                            return true
                        }
                    }
                }
            }
        }
        return false
    }
}
