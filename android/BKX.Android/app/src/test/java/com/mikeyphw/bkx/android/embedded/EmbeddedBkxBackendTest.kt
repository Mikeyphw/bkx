package com.mikeyphw.bkx.android.embedded

import com.mikeyphw.bkx.android.core.backend.BackendReadiness
import com.mikeyphw.bkx.android.core.bridge.BridgeRequestEnvelope
import com.mikeyphw.bkx.android.core.bridge.BridgeRequestFiles
import java.nio.file.Files
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedBkxBackendTest {
    @Test
    fun readinessMapsGoRuntimeWithoutTermux() = runTest {
        val backend = EmbeddedBkxBackend(FakeGateway(), StandardTestDispatcher(testScheduler))

        val readiness = backend.checkReadiness()

        assertTrue(readiness is BackendReadiness.Ready)
        readiness as BackendReadiness.Ready
        assertEquals("go-native-android", readiness.runtime)
        assertEquals(1, readiness.bridgeProtocolVersion)
        assertTrue("backup.plan" in readiness.capabilities)
        assertTrue(readiness.message.contains("embedded"))
    }


    @Test
    fun readinessRejectsRetiredEmbeddedPythonRuntime() = runTest {
        val gateway = FakeGateway(
            readinessDocument = """{"protocol_version":1,"backend_version":"1.0.0","runtime":"embedded-cpython","python_version":"3.14","capabilities":["backup.plan"]}""",
        )
        val backend = EmbeddedBkxBackend(gateway, StandardTestDispatcher(testScheduler))

        val readiness = backend.checkReadiness()

        assertTrue(readiness is BackendReadiness.ProbeFailed)
    }

    @Test
    fun submitPublishesStrictBridgeRequestAndResult() = runTest {
        val gateway = FakeGateway()
        val backend = EmbeddedBkxBackend(gateway, StandardTestDispatcher(testScheduler))
        val root = Files.createTempDirectory("bkx-embedded-test").toFile()
        val requestDir = root.resolve("req-1")
        val files = BridgeRequestFiles(
            requestId = "req-1",
            requestFilePath = requestDir.resolve("request.json").path,
            resultFilePath = requestDir.resolve("result.json").path,
            progressFilePath = requestDir.resolve("progress.jsonl").path,
            cancelFilePath = requestDir.resolve("cancel.json").path,
        )

        val submission = backend.submit(
            request = BridgeRequestEnvelope(requestId = "req-1", operation = "backup.plan"),
            files = files,
        )

        assertTrue(submission.accepted)
        assertTrue(requestDir.resolve("request.json").isFile)
        assertTrue(requestDir.resolve("result.json").isFile)
        assertTrue(gateway.lastRequestJson.orEmpty().contains("\"operation\":\"backup.plan\""))
        assertEquals("req-1", backend.readFinalResult(requestDir.resolve("result.json").readText()).requestId)
    }

    @Test
    fun malformedRuntimeResultFailsClosedBeforePublication() = runTest {
        val gateway = FakeGateway(resultJson = "not-json")
        val backend = EmbeddedBkxBackend(gateway, StandardTestDispatcher(testScheduler))
        val root = Files.createTempDirectory("bkx-embedded-test").toFile()
        val files = BridgeRequestFiles(
            requestId = "req-2",
            requestFilePath = root.resolve("request.json").path,
            resultFilePath = root.resolve("result.json").path,
            progressFilePath = root.resolve("progress.jsonl").path,
            cancelFilePath = root.resolve("cancel.json").path,
        )

        val submission = backend.submit(
            request = BridgeRequestEnvelope(requestId = "req-2", operation = "backup.plan"),
            files = files,
        )

        assertFalse(submission.accepted)
        assertTrue(submission.readiness is BackendReadiness.ProbeFailed)
        assertFalse(root.resolve("result.json").exists())
    }

    @Test
    fun cancelUsesEmbeddedGatewayAndPublishesNoShellCommand() = runTest {
        val gateway = FakeGateway()
        val backend = EmbeddedBkxBackend(gateway, StandardTestDispatcher(testScheduler))
        val root = Files.createTempDirectory("bkx-embedded-test").toFile()
        val cancelFile = root.resolve("cancel.json")

        val submission = backend.cancel("req-cancel", cancelFile.path)

        assertTrue(submission.accepted)
        assertEquals("req-cancel", gateway.cancelledRequestId)
        assertTrue(cancelFile.readText().contains("\"action\":\"cancel\""))
    }

    private class FakeGateway(
        private val resultJson: String = """{"protocol_version":1,"request_id":"req-1","status":"success","summary":"embedded ok","errors":[],"artifacts":[]}""",
        private val readinessDocument: String = """{"protocol_version":1,"backend_version":"0.0.1","runtime":"go-native-android","python_version":"none","capabilities":["backup.plan"]}""",
    ) : EmbeddedRuntimeGateway {
        var lastRequestJson: String? = null
        var cancelledRequestId: String? = null

        override suspend fun readinessJson(): String = readinessDocument

        override suspend fun executeJson(requestJson: String): String {
            lastRequestJson = requestJson
            val requestId = Regex("\\\"request_id\\\":\\\"([^\\\"]+)\\\"").find(requestJson)?.groupValues?.get(1) ?: "req-1"
            return if (resultJson.contains("\"request_id\":\"req-1\"")) {
                resultJson.replace("\"request_id\":\"req-1\"", "\"request_id\":\"$requestId\"")
            } else {
                resultJson
            }
        }

        override suspend fun progressJson(requestId: String): String = "null"

        override suspend fun cancelJson(requestId: String): String {
            cancelledRequestId = requestId
            return """{"protocol_version":1,"request_id":"$requestId","status":"success","summary":"cancel recorded","errors":[],"artifacts":[]}"""
        }
    }
}
