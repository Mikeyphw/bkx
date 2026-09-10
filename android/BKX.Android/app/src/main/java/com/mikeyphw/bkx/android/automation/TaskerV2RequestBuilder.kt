package com.mikeyphw.bkx.android.automation

import com.mikeyphw.bkx.android.core.bridge.BridgeRequestEnvelope
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object TaskerV2RequestBuilder {
    fun runSavedJob(
        requestId: String,
        jobId: String,
        repositoryId: String = "",
        overrides: List<String> = emptyList(),
    ): BridgeRequestEnvelope = BridgeRequestEnvelope(
        requestId = requestId,
        operation = "jobs.run",
        payload = buildJsonObject {
            put("job_id", jobId)
            if (repositoryId.isNotBlank()) put("repository_ref", repositoryId)
            if (overrides.isNotEmpty()) put("overrides", buildJsonArray { overrides.forEach { add(JsonPrimitive(it)) } })
        },
    )

    fun createSnapshot(requestId: String, repositoryId: String, sources: List<String>): BridgeRequestEnvelope = BridgeRequestEnvelope(
        requestId = requestId,
        operation = "backup.snapshot",
        payload = buildJsonObject {
            put("engine", "restic")
            put("repository_ref", repositoryId)
            put("sources", buildJsonArray { sources.forEach { add(JsonPrimitive(it)) } })
            put("verify", true)
        },
    )

    fun repositoryCheck(requestId: String, repositoryId: String): BridgeRequestEnvelope = repositoryRequest(
        requestId, "repository.check", repositoryId,
    )

    fun retentionApply(requestId: String, repositoryId: String, dryRun: Boolean, confirm: Boolean): BridgeRequestEnvelope = BridgeRequestEnvelope(
        requestId = requestId,
        operation = "retention.apply",
        payload = buildJsonObject {
            put("repository_id", repositoryId)
            put("dry_run", dryRun)
            put("confirm", confirm)
        },
    )

    fun repositoryPrune(requestId: String, repositoryId: String, dryRun: Boolean, confirm: Boolean): BridgeRequestEnvelope = BridgeRequestEnvelope(
        requestId = requestId,
        operation = "repository.prune",
        payload = buildJsonObject {
            put("repository_ref", repositoryId)
            put("dry_run", dryRun)
            put("confirm", confirm)
        },
    )

    fun restoreSnapshot(
        requestId: String,
        repositoryId: String,
        snapshotId: String,
        destination: String,
        includes: List<String>,
        overwrite: String,
        dryRun: Boolean,
    ): BridgeRequestEnvelope = BridgeRequestEnvelope(
        requestId = requestId,
        operation = "snapshots.restore",
        payload = buildJsonObject {
            put("repository_ref", repositoryId)
            put("snapshot", snapshotId)
            put("target", destination)
            put("overwrite", overwrite)
            put("dry_run", dryRun)
            if (includes.isNotEmpty()) put("include", buildJsonArray { includes.forEach { add(JsonPrimitive(it)) } })
        },
    )

    fun showRepository(requestId: String, repositoryId: String): BridgeRequestEnvelope = BridgeRequestEnvelope(
        requestId = requestId,
        operation = "repositories.show",
        payload = buildJsonObject { put("repository_id", repositoryId) },
    )

    fun showJob(requestId: String, jobId: String): BridgeRequestEnvelope = BridgeRequestEnvelope(
        requestId = requestId,
        operation = "jobs.show",
        payload = buildJsonObject { put("job_id", jobId) },
    )

    private fun repositoryRequest(requestId: String, operation: String, repositoryId: String) = BridgeRequestEnvelope(
        requestId = requestId,
        operation = operation,
        payload = buildJsonObject { put("repository_ref", repositoryId) },
    )
}
