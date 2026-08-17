package com.mikeyphw.bkx.android.core.runs

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RunSummaryStoreTest {
    @Test
    fun storesRecentRunSummariesWithoutSensitiveArtifactPaths() = runTest {
        val store = InMemoryRunSummaryStore()
        store.upsert(
            RunSummary(
                requestId = "req-1",
                operation = "backend.capabilities",
                status = RunStatus.Success,
                startedAtEpochMillis = 100,
                finishedAtEpochMillis = 150,
                durationMillis = 50,
                artifacts = listOf(RunArtifactSummary(label = "report", displayName = "result.json", sha256 = "a".repeat(64))),
                verification = VerificationState.Passed,
                summary = "completed",
            ),
        )

        val recent = store.recentRuns()

        assertEquals("result.json", recent.single().artifacts.single().displayName)
        assertEquals(VerificationState.Passed, recent.single().verification)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAbsoluteArtifactPathPersistence() {
        RunArtifactSummary(label = "unsafe", displayName = "/tmp/private/result.json")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWindowsStyleArtifactPathPersistence() {
        RunArtifactSummary(label = "unsafe", displayName = "private\\result.json")
    }

    @Test
    fun allowsHumanDisplayNamesWithApostrophes() {
        val artifact = RunArtifactSummary(label = "safe", displayName = "Josh's result.json")
        assertEquals("Josh's result.json", artifact.displayName)
    }
}
