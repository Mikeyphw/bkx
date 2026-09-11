package com.mikeyphw.bkx.android.core.jobs

import com.mikeyphw.bkx.android.core.bridge.BridgeArtifact
import com.mikeyphw.bkx.android.core.bridge.BridgeJson
import com.mikeyphw.bkx.android.core.bridge.BridgeRequestEnvelope
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object SavedJobRequestBuilder {
    const val OPERATION_LIST = "jobs.list"
    const val OPERATION_SHOW = "jobs.show"
    const val OPERATION_CREATE = "jobs.create"
    const val OPERATION_UPDATE = "jobs.update"
    const val OPERATION_CLONE = "jobs.clone"
    const val OPERATION_DELETE = "jobs.delete"
    const val OPERATION_PLAN = "jobs.plan"
    const val OPERATION_RUN = "jobs.run"
    const val OPERATION_REPLAY_RUN = "runs.replay"

    fun list(requestId: String): BridgeRequestEnvelope = BridgeRequestEnvelope(requestId = requestId, operation = OPERATION_LIST)
    fun show(requestId: String, jobId: String): BridgeRequestEnvelope = idRequest(requestId, OPERATION_SHOW, jobId)
    fun delete(requestId: String, jobId: String): BridgeRequestEnvelope = idRequest(requestId, OPERATION_DELETE, jobId)

    fun create(requestId: String, draft: SavedJobDraft): BridgeRequestEnvelope = BridgeRequestEnvelope(
        requestId = requestId,
        operation = OPERATION_CREATE,
        payload = draftPayload(draft),
    )

    fun update(requestId: String, jobId: String, expectedRevision: Long, draft: SavedJobDraft): BridgeRequestEnvelope {
        require(expectedRevision >= 1L) { "expected revision must be positive" }
        require(draft.jobId == jobId) { "job update cannot change job id" }
        return BridgeRequestEnvelope(
            requestId = requestId,
            operation = OPERATION_UPDATE,
            payload = buildJsonObject {
                draftPayload(draft).forEach { (key, value) -> put(key, value) }
                put("expected_revision", expectedRevision)
            },
        )
    }

    fun clone(requestId: String, sourceJobId: String, targetJobId: String, name: String = targetJobId): BridgeRequestEnvelope = BridgeRequestEnvelope(
        requestId = requestId,
        operation = OPERATION_CLONE,
        payload = buildJsonObject {
            put("source_job_id", requireJobId(sourceJobId))
            put("target_job_id", requireJobId(targetJobId))
            put("name", name.trim().also { require(it.isNotBlank()) { "clone name must not be blank" } })
        },
    )

    fun plan(requestId: String, jobId: String, repositoryBackendHint: String? = null): BridgeRequestEnvelope = executionRequest(requestId, OPERATION_PLAN, jobId, repositoryBackendHint)
    fun run(requestId: String, jobId: String, repositoryBackendHint: String? = null): BridgeRequestEnvelope = executionRequest(requestId, OPERATION_RUN, jobId, repositoryBackendHint)
    fun replay(requestId: String, runId: String, jobId: String? = null): BridgeRequestEnvelope = BridgeRequestEnvelope(
        requestId = requestId,
        operation = OPERATION_REPLAY_RUN,
        payload = buildJsonObject {
            put("run_id", runId.trim().also { require(it.isNotBlank()) { "run id must not be blank" } })
            jobId?.trim()?.takeIf { it.isNotBlank() }?.let { put("job_id", requireJobId(it)) }
        },
    )

    private fun executionRequest(requestId: String, operation: String, jobId: String, repositoryBackendHint: String?): BridgeRequestEnvelope = BridgeRequestEnvelope(
        requestId = requestId,
        operation = operation,
        payload = buildJsonObject {
            put("job_id", requireJobId(jobId))
            repositoryBackendHint?.trim()?.takeIf { it.isNotBlank() }?.let { put("repository_backend", it) }
        },
    )

    private fun idRequest(requestId: String, operation: String, jobId: String): BridgeRequestEnvelope = BridgeRequestEnvelope(
        requestId = requestId,
        operation = operation,
        payload = buildJsonObject { put("job_id", requireJobId(jobId)) },
    )

    private fun draftPayload(draft: SavedJobDraft): JsonObject = buildJsonObject {
        put("job_id", draft.jobId)
        put("name", draft.name)
        put("description", draft.description)
        put("tags", strings(draft.tags))
        put("enabled", draft.enabled)
        put("operation", operationJson(draft.operation))
    }

    private fun operationJson(operation: OperationSpecV2): JsonObject = BridgeJson.json.parseToJsonElement(
        BridgeJson.json.encodeToString(OperationSpecV2.serializer(), operation),
    ) as JsonObject

    private fun strings(values: List<String>): JsonArray = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }
    private fun requireJobId(jobId: String): String = jobId.trim().also { require(it.matches(Regex("^[a-z0-9][a-z0-9._-]{0,127}$"))) { "invalid job id" } }
}

object SavedJobBridgeCodec {
    fun decodeArtifacts(artifacts: List<BridgeArtifact>): List<SavedJob> = artifacts.mapNotNull(::decodeArtifact)

    fun decodeArtifact(artifact: BridgeArtifact): SavedJob? {
        if (artifact.label != "saved_job") return null
        val raw = artifact.metadata["saved_job_json"] ?: return null
        return runCatching { BridgeJson.json.decodeFromString<SavedJob>(raw) }.getOrNull()
    }
}
