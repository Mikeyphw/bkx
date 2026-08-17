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
    fun sourceDefinesThinBackendPortAndTermuxAdapterBoundary() {
        assertTrue(sourceText.contains("interface BackendPort"))
        assertTrue(sourceText.contains("class TermuxBridgeAdapter"))
        assertTrue(sourceText.contains("class FakeBackendPort"))
        assertTrue(sourceText.contains("sealed interface BackendReadiness"))
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
