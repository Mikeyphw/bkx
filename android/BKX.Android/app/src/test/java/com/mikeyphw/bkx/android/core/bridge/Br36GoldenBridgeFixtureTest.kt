package com.mikeyphw.bkx.android.core.bridge

import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Br36GoldenBridgeFixtureTest {
    @Test
    fun goldenBackupPlanRequestDecodesAndReencodesThroughAndroidBridgeContract() {
        val raw = fixture("bridge/br36_request_backup_plan.json")
        val request = BridgeJson.json.decodeFromString<BridgeRequestEnvelope>(raw)

        assertEquals(1, request.protocolVersion)
        assertEquals("br36-golden-backup-plan", request.requestId)
        assertEquals("backup.plan", request.operation)
        val encoded = BridgeJson.encodeRequest(request)
        assertTrue(encoded.contains("\"compression\":\"zstd\""))
        assertTrue(encoded.contains("\"dry_run\":true"))
    }

    @Test
    fun goldenFailureResultDecodesWithTypedErrorAndMetrics() {
        val result = BridgeJson.parseFinalResult(
            fixture("bridge/br36_result_failure.json"),
            expectedRequestId = "br36-golden-failure",
        )

        assertEquals(BridgeResultStatus.Failure, result.status)
        assertEquals("validation_error", result.errors.single().code)
        assertEquals("invalid input", result.errors.single().message)
        assertEquals(42L, result.metrics?.durationMillis)
    }

    private fun fixture(path: String): String = requireNotNull(javaClass.classLoader?.getResource(path)) {
        "Missing BR36 fixture $path"
    }.readText()
}
