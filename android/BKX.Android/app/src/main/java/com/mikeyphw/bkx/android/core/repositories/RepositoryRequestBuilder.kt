package com.mikeyphw.bkx.android.core.repositories

import com.mikeyphw.bkx.android.core.backup.BackupEngineKind
import com.mikeyphw.bkx.android.core.bridge.BridgeRequestEnvelope
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object RepositoryRequestBuilder {
    const val OPERATION_ADD = "repositories.add"
    const val OPERATION_LIST = "repositories.list"
    const val OPERATION_SHOW = "repositories.show"
    const val OPERATION_REMOVE = "repositories.remove"
    const val OPERATION_INIT = "repository.init"
    const val OPERATION_SNAPSHOT = "backup.snapshot"

    fun add(requestId: String, spec: RepositoryCreateSpec): BridgeRequestEnvelope = BridgeRequestEnvelope(
        requestId = requestId,
        operation = OPERATION_ADD,
        payload = buildJsonObject {
            put("repository_id", spec.repositoryId)
            put("name", spec.name)
            put("engine", spec.engine)
            put("backend", spec.backend)
            put("location", spec.location)
            spec.credentialRef?.let { put("credential_ref", it) }
        },
    )

    fun list(requestId: String): BridgeRequestEnvelope = BridgeRequestEnvelope(
        requestId = requestId,
        operation = OPERATION_LIST,
    )

    fun show(requestId: String, repositoryId: String): BridgeRequestEnvelope = repositoryIdRequest(requestId, OPERATION_SHOW, repositoryId)

    fun remove(requestId: String, repositoryId: String): BridgeRequestEnvelope = repositoryIdRequest(requestId, OPERATION_REMOVE, repositoryId)

    fun initialize(requestId: String, repository: RepositoryDefinition): BridgeRequestEnvelope = BridgeRequestEnvelope(
        requestId = requestId,
        operation = OPERATION_INIT,
        payload = buildJsonObject {
            put("repository_ref", requireRepositoryIdValue(repository.repositoryId))
            put("repository_backend", repository.backend)
        },
    )

    fun snapshot(
        requestId: String,
        repository: RepositoryDefinition,
        sources: List<String>,
        tags: List<String> = emptyList(),
        excludes: List<String> = emptyList(),
        verify: Boolean = true,
    ): BridgeRequestEnvelope {
        require(sources.isNotEmpty() && sources.none { it.isBlank() }) { "snapshot sources must not be blank" }
        return BridgeRequestEnvelope(
            requestId = requestId,
            operation = OPERATION_SNAPSHOT,
            payload = buildJsonObject {
                put("engine", BackupEngineKind.Restic.wireValue)
                put("repository_ref", requireRepositoryIdValue(repository.repositoryId))
                put("repository_backend", repository.backend)
                put("sources", kotlinx.serialization.json.buildJsonArray { sources.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                put("tags", kotlinx.serialization.json.buildJsonArray { tags.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                put("excludes", kotlinx.serialization.json.buildJsonArray { excludes.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                put("verify", verify)
            },
        )
    }

    private fun repositoryIdRequest(requestId: String, operation: String, repositoryId: String) = BridgeRequestEnvelope(
        requestId = requestId,
        operation = operation,
        payload = buildJsonObject { put("repository_id", requireRepositoryIdValue(repositoryId)) },
    )

    private fun requireRepositoryIdValue(value: String): String {
        val normalized = value.trim()
        require(normalized.matches(Regex("^[a-z0-9][a-z0-9._-]{0,127}$"))) { "invalid repository id" }
        return normalized
    }
}
