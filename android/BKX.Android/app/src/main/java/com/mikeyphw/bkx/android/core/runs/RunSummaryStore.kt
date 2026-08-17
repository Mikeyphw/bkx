package com.mikeyphw.bkx.android.core.runs

interface RunSummaryStore {
    suspend fun recentRuns(limit: Int = 20): List<RunSummary>
    suspend fun upsert(summary: RunSummary)
}

class InMemoryRunSummaryStore(
    initial: List<RunSummary> = emptyList(),
) : RunSummaryStore {
    private val runs = LinkedHashMap<String, RunSummary>()

    init {
        initial.forEach { runs[it.requestId] = it }
    }

    override suspend fun recentRuns(limit: Int): List<RunSummary> = runs.values
        .sortedByDescending { it.startedAtEpochMillis }
        .take(limit)

    override suspend fun upsert(summary: RunSummary) {
        runs[summary.requestId] = summary
    }
}
