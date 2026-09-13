package com.mikeyphw.bkx.android.core.operations

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationClassifierTest {
    @Test
    fun directForegroundOperationsClassifyAsThemselves() {
        assertEquals("backup.snapshot", OperationClassifier.effectiveOperation("backup.snapshot"))
        assertTrue(OperationClassifier.requiresForeground("backup.snapshot"))
    }

    @Test
    fun savedJobWrappersHonorEffectiveOperationHint() {
        val payload = buildJsonObject { put("effective_operation", "backup.snapshot") }

        assertEquals("backup.snapshot", OperationClassifier.effectiveOperation("jobs.run", payload))
        assertTrue(OperationClassifier.requiresForeground(OperationClassifier.effectiveOperation("jobs.run", payload)))
    }

    @Test
    fun nonExecutionWrappersIgnoreEffectiveOperationHint() {
        val payload = buildJsonObject { put("effective_operation", "backup.snapshot") }

        assertEquals("jobs.plan", OperationClassifier.effectiveOperation("jobs.plan", payload))
        assertFalse(OperationClassifier.requiresForeground(OperationClassifier.effectiveOperation("jobs.plan", payload)))
    }

    @Test
    fun activeAndTerminalStatusSetsAreExplicitAndMutuallyExclusive() {
        assertTrue(OperationClassifier.isActive(ActiveOperationStatus.Cancelling))
        assertTrue(OperationClassifier.isTerminal(ActiveOperationStatus.Cancelled))
        assertFalse(OperationClassifier.isActive(ActiveOperationStatus.Cancelled))
        assertFalse(OperationClassifier.isTerminal(ActiveOperationStatus.Running))
    }
}
