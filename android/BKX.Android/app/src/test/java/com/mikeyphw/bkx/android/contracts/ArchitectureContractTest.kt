package com.mikeyphw.bkx.android.contracts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ArchitectureContractTest {
    private val sourceRoot = File("src/main/java")
    private val sourceText = sourceRoot.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .joinToString("\n") { it.readText() }

    @Test
    fun sourceDefinesEmbeddedRootAwareBackendWithoutTermuxExecutionAdapter() {
        assertTrue(sourceText.contains("interface BackendPort"))
        assertTrue(sourceText.contains("class EmbeddedBkxBackend"))
        assertTrue(sourceText.contains("class ChaquopyPythonGateway"))
        assertTrue(sourceText.contains("class RootAwarePythonGateway"))
        assertTrue(sourceText.contains("class RootBkxServiceClient"))
        assertTrue(sourceText.contains("class BkxRootEngineService"))
        assertTrue(sourceText.contains("class FakeBackendPort"))
        assertTrue(sourceText.contains("sealed interface BackendReadiness"))
        assertFalse(sourceText.contains("class TermuxBridgeAdapter"))
    }

    @Test
    fun sourceDoesNotUseArbitraryShellExecutionApis() {
        val forbidden = listOf(
            "Runtime.getRuntime().exec",
            "ProcessBuilder(",
            "sh -c",
            "bash -c",
            "/system/bin/sh",
            "su -c",
        )
        forbidden.forEach { token ->
            assertFalse("Forbidden shell execution token found: $token", sourceText.contains(token))
        }
    }
}
