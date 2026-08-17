package com.mikeyphw.bkx.android.termux

import com.mikeyphw.bkx.android.core.bridge.BridgeRequestFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxRunCommandContractTest {
    private fun requestJson(): String = """{"protocol_version":1,"request_id":"req-123","operation":"backend.capabilities","payload":{}}"""

    private val files = BridgeRequestFiles(
        requestId = "req-123",
        requestFilePath = "${TermuxRunCommandContract.TERMUX_HOME}/.bkx-android/req-123/request.json",
        resultFilePath = "${TermuxRunCommandContract.TERMUX_HOME}/.bkx-android/req-123/result.json",
        progressFilePath = "${TermuxRunCommandContract.TERMUX_HOME}/.bkx-android/req-123/progress.jsonl",
        cancelFilePath = "${TermuxRunCommandContract.TERMUX_HOME}/.bkx-android/req-123/cancel.json",
    )

    @Test
    fun invocationUsesOnlyApprovedWrapper() {
        val invocation = TermuxRunCommandInvocationFactory().create(files, requestJson())

        assertEquals(TermuxRunCommandContract.APPROVED_WRAPPER_PATH, invocation.commandPath)
        assertEquals(TermuxRunCommandContract.TERMUX_PACKAGE_NAME, invocation.packageName)
        assertEquals(TermuxRunCommandContract.RUN_COMMAND_SERVICE_CLASS, invocation.serviceClassName)
        assertEquals(TermuxRunCommandContract.ACTION_RUN_COMMAND, invocation.action)
    }

    @Test
    fun invocationSerializesStructuredPathsAndRequestIdAsArguments() {
        val invocation = TermuxRunCommandInvocationFactory().create(files, requestJson())

        assertEquals("req-123", invocation.arguments[invocation.arguments.indexOf("--request-id") + 1])
        assertTrue(invocation.arguments.contains(files.requestFilePath))
        assertTrue(invocation.arguments.contains(files.resultFilePath))
        assertTrue(invocation.arguments.contains(files.progressFilePath))
        assertTrue(invocation.arguments.contains(files.cancelFilePath))
    }

    @Test
    fun invocationCarriesOneStructuredRequestDocumentViaStdinNotShellArguments() {
        val invocation = TermuxRunCommandInvocationFactory().create(files, requestJson())

        assertTrue(invocation.stdin.startsWith("{"))
        assertTrue(invocation.stdin.contains("\"request_id\":\"req-123\""))
        assertFalse(invocation.arguments.joinToString(" ").contains("backend.capabilities"))
    }

    @Test
    fun cancelInvocationUsesFixedWrapperAndRequestIdCancelJson() {
        val invocation = TermuxRunCommandInvocationFactory().createCancel(
            requestId = "req-123",
            cancelFilePath = files.cancelFilePath,
            cancelJson = """{"protocol_version":1,"request_id":"req-123","action":"cancel"}""",
        )

        assertEquals(TermuxRunCommandContract.APPROVED_WRAPPER_PATH, invocation.commandPath)
        assertEquals("req-123", invocation.arguments[invocation.arguments.indexOf("--request-id") + 1])
        assertEquals(files.cancelFilePath, invocation.arguments[invocation.arguments.indexOf("--cancel") + 1])
        assertTrue(invocation.arguments.contains("run.cancel"))
        assertTrue(invocation.stdin.contains(""action":"cancel""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonApprovedWrapperIdentity() {
        ApprovedWrapperIdentity(
            absolutePath = "${TermuxRunCommandContract.TERMUX_HOME}/../usr/bin/bash",
            label = "unsafe",
        )
    }

    @Test
    fun argumentsDoNotContainShellInterpreterOrCompoundCommandText() {
        val invocation = TermuxRunCommandInvocationFactory().create(files, requestJson())
        val joined = invocation.arguments.joinToString(" ")

        assertFalse(joined.contains("sh -c"))
        assertFalse(joined.contains("bash -c"))
        assertFalse(joined.contains("&&"))
        assertFalse(joined.contains(";"))
        assertFalse(joined.contains("|"))
    }
}
