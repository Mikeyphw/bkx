package com.mikeyphw.bkx.android.automation

import com.mikeyphw.bkx.android.core.jobs.SavedJob
import com.mikeyphw.bkx.android.core.jobs.SavedJobStoreDocument
import com.mikeyphw.bkx.android.core.repositories.RepositoryDefinition
import com.mikeyphw.bkx.android.core.state.BkxSharedStateContract
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Read-only Tasker configuration catalog backed by BKX's shared non-secret control plane. */
data class BkxTaskerCatalog(
    val jobs: List<SavedJob> = emptyList(),
    val repositories: List<RepositoryDefinition> = emptyList(),
)

object BkxTaskerSharedCatalog {
    private val json = Json { ignoreUnknownKeys = true }

    fun read(root: File = File(BkxSharedStateContract.ROOT)): BkxTaskerCatalog {
        val jobs = runCatching {
            json.decodeFromString<SavedJobStoreDocument>(root.resolve("jobs/jobs.json").readText()).jobs
        }.getOrDefault(emptyList())
        val repositories = runCatching {
            json.decodeFromString<RepositoryDocument>(root.resolve("repositories/repositories.json").readText()).repositories
        }.getOrDefault(emptyList())
        return BkxTaskerCatalog(
            jobs = jobs.sortedWith(compareBy<SavedJob> { it.name.lowercase() }.thenBy { it.jobId }),
            repositories = repositories.sortedWith(compareBy<RepositoryDefinition> { it.name.lowercase() }.thenBy { it.repositoryId }),
        )
    }

    @Serializable
    private data class RepositoryDocument(
        val schema_version: Int = 1,
        val repositories: List<RepositoryDefinition> = emptyList(),
    )
}
