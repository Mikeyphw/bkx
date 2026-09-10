package com.mikeyphw.bkx.android.app.store

import android.content.Context
import androidx.core.content.edit
import com.mikeyphw.bkx.android.core.jobs.OPERATION_SPEC_V2_SCHEMA_VERSION
import com.mikeyphw.bkx.android.core.jobs.OperationSpecV2
import com.mikeyphw.bkx.android.core.jobs.SAVED_JOB_SCHEMA_VERSION
import com.mikeyphw.bkx.android.core.jobs.SavedJob
import com.mikeyphw.bkx.android.core.jobs.SavedJobDraft
import com.mikeyphw.bkx.android.core.jobs.SavedJobRevisionConflictException
import com.mikeyphw.bkx.android.core.jobs.SavedJobStore
import com.mikeyphw.bkx.android.core.jobs.SavedJobStoreDocument
import com.mikeyphw.bkx.android.core.jobs.currentUtcIso
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SharedPreferencesSavedJobStore(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    private val nowIso: () -> String = ::currentUtcIso,
) : SavedJobStore {
    private val prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    override suspend fun list(): List<SavedJob> = synchronized(lock) {
        readDocument().jobs.sortedWith(compareBy<SavedJob> { it.name }.thenBy { it.jobId })
    }

    override suspend fun get(jobId: String): SavedJob? = synchronized(lock) {
        readDocument().jobs.firstOrNull { it.jobId == jobId }
    }

    override suspend fun create(draft: SavedJobDraft): SavedJob = synchronized(lock) {
        val document = readDocument()
        require(document.jobs.none { it.jobId == draft.jobId }) { "saved job already exists: ${draft.jobId}" }
        val now = nowIso()
        val created = SavedJob(
            jobId = draft.jobId,
            name = draft.name,
            description = draft.description,
            tags = draft.tags,
            revision = 1,
            enabled = draft.enabled,
            operation = draft.operation,
            createdAt = now,
            updatedAt = now,
        )
        writeDocument(document.copy(jobs = document.jobs + created))
        created
    }

    override suspend fun update(jobId: String, expectedRevision: Long, draft: SavedJobDraft): SavedJob = synchronized(lock) {
        require(draft.jobId == jobId) { "saved job update cannot change job_id" }
        val document = readDocument()
        val current = document.jobs.firstOrNull { it.jobId == jobId } ?: throw NoSuchElementException("saved job not found: $jobId")
        if (current.revision != expectedRevision) {
            throw SavedJobRevisionConflictException(jobId, expectedRevision, current.revision)
        }
        val updated = SavedJob(
            jobId = current.jobId,
            name = draft.name,
            description = draft.description,
            tags = draft.tags,
            revision = current.revision + 1,
            enabled = draft.enabled,
            operation = draft.operation,
            createdAt = current.createdAt,
            updatedAt = nowIso(),
        )
        val jobs = document.jobs.map { if (it.jobId == jobId) updated else it }
        val revisions = document.revisions.toMutableMap().apply {
            this[jobId] = get(jobId).orEmpty() + current
        }
        writeDocument(document.copy(jobs = jobs, revisions = revisions))
        updated
    }

    override suspend fun delete(jobId: String): Boolean = synchronized(lock) {
        val document = readDocument()
        if (document.jobs.none { it.jobId == jobId }) return@synchronized false
        writeDocument(
            document.copy(
                jobs = document.jobs.filterNot { it.jobId == jobId },
                revisions = document.revisions - jobId,
            ),
        )
        true
    }

    override suspend fun revisions(jobId: String): List<SavedJob> = synchronized(lock) {
        val document = readDocument()
        (document.revisions[jobId].orEmpty() + document.jobs.filter { it.jobId == jobId }).sortedBy { it.revision }
    }

    private fun readDocument(): SavedJobStoreDocument {
        val raw = prefs.getString(KEY_DOCUMENT, null) ?: return SavedJobStoreDocument()
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrElse {
            throw IllegalStateException("saved-job store is not valid JSON", it)
        }
        return when (root["schema_version"]?.jsonPrimitive?.intOrNull ?: 1) {
            SAVED_JOB_SCHEMA_VERSION -> json.decodeFromString<SavedJobStoreDocument>(raw)
            1 -> migrateV1(json.decodeFromString<LegacySavedJobDocumentV1>(raw)).also(::writeDocument)
            else -> throw IllegalStateException("unsupported saved-job store schema_version")
        }
    }

    private fun migrateV1(legacy: LegacySavedJobDocumentV1): SavedJobStoreDocument {
        val now = nowIso()
        return SavedJobStoreDocument(
            jobs = legacy.jobs.map { old ->
                val createdAt = old.createdAt.ifBlank { now }
                SavedJob(
                    schemaVersion = SAVED_JOB_SCHEMA_VERSION,
                    jobId = old.jobId,
                    name = old.name,
                    description = old.description,
                    tags = old.tags,
                    revision = 1,
                    enabled = old.enabled,
                    operation = OperationSpecV2(
                        schemaVersion = OPERATION_SPEC_V2_SCHEMA_VERSION,
                        operation = old.operation,
                        parameters = old.parameters,
                        repositoryRef = old.repositoryRef,
                    ),
                    createdAt = createdAt,
                    updatedAt = old.updatedAt.ifBlank { createdAt },
                )
            },
        )
    }

    private fun writeDocument(document: SavedJobStoreDocument) {
        prefs.edit(commit = true) { putString(KEY_DOCUMENT, json.encodeToString(document)) }
    }

    private companion object {
        const val PREFERENCES_NAME = "bkx_saved_jobs"
        const val KEY_DOCUMENT = "saved_job_document"
    }
}

@Serializable
private data class LegacySavedJobDocumentV1(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val jobs: List<LegacySavedJobV1> = emptyList(),
)

@Serializable
private data class LegacySavedJobV1(
    @SerialName("job_id") val jobId: String,
    val name: String,
    val description: String = "",
    val tags: List<String> = emptyList(),
    val enabled: Boolean = true,
    val operation: String,
    val parameters: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.buildJsonObject { },
    @SerialName("repository_ref") val repositoryRef: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)
