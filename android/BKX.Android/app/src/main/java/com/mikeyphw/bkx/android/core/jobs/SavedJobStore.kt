package com.mikeyphw.bkx.android.core.jobs

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

interface SavedJobStore {
    suspend fun list(): List<SavedJob>
    suspend fun get(jobId: String): SavedJob?
    suspend fun create(draft: SavedJobDraft): SavedJob
    suspend fun update(jobId: String, expectedRevision: Long, draft: SavedJobDraft): SavedJob
    suspend fun delete(jobId: String): Boolean
    suspend fun revisions(jobId: String): List<SavedJob>
    suspend fun revision(jobId: String, revision: Long): SavedJob? = revisions(jobId).firstOrNull { it.revision == revision }
}

class SavedJobRevisionConflictException(
    val jobId: String,
    val expectedRevision: Long,
    val currentRevision: Long,
) : IllegalStateException("saved job revision conflict for $jobId: expected $expectedRevision, current $currentRevision")

class InMemorySavedJobStore(
    private val nowIso: () -> String = ::currentUtcIso,
) : SavedJobStore {
    private val jobs = linkedMapOf<String, SavedJob>()
    private val history = linkedMapOf<String, MutableList<SavedJob>>()

    override suspend fun list(): List<SavedJob> = synchronized(this) {
        jobs.values.sortedWith(compareBy<SavedJob> { it.name }.thenBy { it.jobId })
    }

    override suspend fun get(jobId: String): SavedJob? = synchronized(this) { jobs[jobId] }

    override suspend fun create(draft: SavedJobDraft): SavedJob = synchronized(this) {
        require(draft.jobId !in jobs) { "saved job already exists: ${draft.jobId}" }
        val now = nowIso()
        SavedJob(
            jobId = draft.jobId,
            name = draft.name,
            description = draft.description,
            tags = draft.tags,
            revision = 1,
            enabled = draft.enabled,
            operation = draft.operation,
            createdAt = now,
            updatedAt = now,
        ).also { jobs[it.jobId] = it }
    }

    override suspend fun update(jobId: String, expectedRevision: Long, draft: SavedJobDraft): SavedJob = synchronized(this) {
        require(draft.jobId == jobId) { "saved job update cannot change job_id" }
        val current = jobs[jobId] ?: throw NoSuchElementException("saved job not found: $jobId")
        if (current.revision != expectedRevision) {
            throw SavedJobRevisionConflictException(jobId, expectedRevision, current.revision)
        }
        history.getOrPut(jobId) { mutableListOf() }.add(current)
        SavedJob(
            jobId = current.jobId,
            name = draft.name,
            description = draft.description,
            tags = draft.tags,
            revision = current.revision + 1,
            enabled = draft.enabled,
            operation = draft.operation,
            createdAt = current.createdAt,
            updatedAt = nowIso(),
        ).also { jobs[jobId] = it }
    }

    override suspend fun delete(jobId: String): Boolean = synchronized(this) {
        val removed = jobs.remove(jobId) != null
        if (removed) history.remove(jobId)
        removed
    }

    override suspend fun revisions(jobId: String): List<SavedJob> = synchronized(this) {
        buildList {
            addAll(history[jobId].orEmpty())
            jobs[jobId]?.let(::add)
        }.sortedBy { it.revision }
    }
}

internal fun currentUtcIso(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}.format(Date())
