package com.mikeyphw.bkx.android.core.bridge

import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Test

class BridgeJsonTest {
    @Test
    fun parsesExactlyOneFinalResultDocument() {
        val result = BridgeJson.parseFinalResult(
            """
            {"protocol_version":1,"request_id":"abc","status":"success","summary":"done"}
            """.trimIndent(),
        )

        assertEquals("abc", result.requestId)
        assertEquals(BridgeResultStatus.Success, result.status)
    }

    @Test(expected = BridgeProtocolException::class)
    fun rejectsMultipleFinalResultDocuments() {
        BridgeJson.parseFinalResult(
            """
            {"protocol_version":1,"request_id":"abc","status":"success"}
            {"protocol_version":1,"request_id":"abc","status":"failure"}
            """.trimIndent(),
        )
    }

    @Test(expected = BridgeProtocolException::class)
    fun rejectsUnsupportedProtocolVersion() {
        BridgeJson.parseFinalResult(
            """
            {"protocol_version":2,"request_id":"abc","status":"success"}
            """.trimIndent(),
        )
    }

    @Test(expected = BridgeProtocolException::class)
    fun rejectsMalformedResultJson() {
        BridgeJson.parseFinalResult("not-json")
    }

    @Test
    fun encodesVersionedRequestAndCancelDocuments() {
        val request = BridgeRequestEnvelope(requestId = "req-1", operation = "backend.capabilities")
        val requestJson = BridgeJson.encodeRequest(request)
        val cancelJson = BridgeJson.encodeCancel(BridgeCancelEnvelope(requestId = "req-1"))

        assertEquals(request, BridgeJson.json.decodeFromString<BridgeRequestEnvelope>(requestJson))
        assertEquals("cancel", BridgeJson.json.decodeFromString<BridgeCancelEnvelope>(cancelJson).action)
    }
}
