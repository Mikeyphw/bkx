package com.mikeyphw.bkx.android.core.rendering

import com.mikeyphw.bkx.android.core.bridge.BridgeProtocolException
import com.mikeyphw.bkx.android.core.operations.OperationFailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BkxBridgeResponseParserTest {
    @Test
    fun parsesOneSchemaResponseDocument() {
        val response = BkxBridgeResponseParser.parseStrictFinalResponse(
            """
            {"protocol_version":1,"request_id":"req-1","ok":false,"operation":"backup.create","warnings":[],"errors":[{"code":"BKX_BACKEND_TIMEOUT","message":"timeout"}],"verification":{"status":"unknown","evidence":[]},"artifacts":[],"completed_at_ms":10}
            """.trimIndent(),
        )
        val rendered = BkxResultRenderer.render(response)

        assertEquals("req-1", response.requestId)
        assertTrue(rendered is BkxResultRenderingState.Failed)
        assertEquals(OperationFailureKind.BackendTimeout, (rendered as BkxResultRenderingState.Failed).failure.kind)
    }


    @Test
    fun verificationFailureRendersAsFailureEvenWhenOkIsTrue() {
        val response = BkxBridgeResponseParser.parseStrictFinalResponse(
            """
            {"protocol_version":1,"request_id":"req-1","ok":true,"operation":"backup.create","warnings":[],"errors":[],"verification":{"status":"failed","evidence":[]},"artifacts":[],"completed_at_ms":10}
            """.trimIndent(),
        )

        assertTrue(BkxResultRenderer.render(response) is BkxResultRenderingState.Failed)
    }

    @Test(expected = BridgeProtocolException::class)
    fun rejectsMultipleSchemaResponseDocuments() {
        BkxBridgeResponseParser.parseStrictFinalResponse(
            """
            {"protocol_version":1,"request_id":"req-1","ok":true,"operation":"backup.plan","warnings":[],"errors":[],"verification":{"status":"passed","evidence":[]},"artifacts":[],"completed_at_ms":10}
            {"protocol_version":1,"request_id":"req-1","ok":false,"operation":"backup.plan","warnings":[],"errors":[],"verification":{"status":"failed","evidence":[]},"artifacts":[],"completed_at_ms":11}
            """.trimIndent(),
        )
    }

    @Test(expected = BridgeProtocolException::class)
    fun rejectsPartialSchemaResponseDocument() {
        BkxBridgeResponseParser.parseStrictFinalResponse(
            """
            {"protocol_version":1,"request_id":"req-1","ok":true,"operation":"backup.plan","warnings":[],"errors":[],"verification":{"status":"passed","evidence":[]},"artifacts":[],"completed_at_ms":10
            """.trimIndent(),
        )
    }

    @Test(expected = BridgeProtocolException::class)
    fun rejectsUnsupportedProtocolVersion() {
        BkxBridgeResponseParser.parseStrictFinalResponse(
            """
            {"protocol_version":999,"request_id":"req-1","ok":true,"operation":"backup.plan","warnings":[],"errors":[],"verification":{"status":"passed","evidence":[]},"artifacts":[],"completed_at_ms":10}
            """.trimIndent(),
        )
    }

    @Test(expected = BridgeProtocolException::class)
    fun rejectsUnknownFieldsBecauseSchemaIsFailClosed() {
        BkxBridgeResponseParser.parseStrictFinalResponse(
            """
            {"protocol_version":1,"request_id":"req-1","ok":true,"operation":"backup.plan","warnings":[],"errors":[],"verification":{"status":"passed","evidence":[]},"artifacts":[],"completed_at_ms":10,"extra":true}
            """.trimIndent(),
        )
    }
}
