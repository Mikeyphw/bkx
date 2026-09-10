package com.mikeyphw.bkx.android.core.repositories

import com.mikeyphw.bkx.android.core.bridge.BridgeArtifact
import com.mikeyphw.bkx.android.core.bridge.BridgeJson
import kotlinx.serialization.decodeFromString

object RepositoryBridgeCodec {
    fun decodeArtifacts(artifacts: List<BridgeArtifact>): List<RepositoryDefinition> = artifacts.mapNotNull(::decodeArtifact)

    fun decodeArtifact(artifact: BridgeArtifact): RepositoryDefinition? {
        if (artifact.label != "repository") return null
        val metadata = artifact.metadata
        val repositoryId = metadata["repository_id"] ?: return null
        val name = metadata["name"] ?: repositoryId
        val backend = metadata["backend"] ?: return null
        val engine = metadata["engine"] ?: "restic"
        val createdAt = metadata["created_at"] ?: return null
        val updatedAt = metadata["updated_at"] ?: return null
        val schemaVersion = metadata["schema_version"]?.toIntOrNull() ?: REPOSITORY_DEFINITION_SCHEMA_VERSION
        val managed = metadata["managed_by_bkx"]?.toBooleanStrictOrNull() ?: false
        val retention = metadata["retention"]?.let { raw -> runCatching { BridgeJson.json.decodeFromString<RetentionPolicy>(raw) }.getOrNull() }
        return runCatching {
            RepositoryDefinition(
                schemaVersion = schemaVersion,
                repositoryId = repositoryId,
                name = name,
                engine = engine,
                backend = backend,
                location = artifact.path,
                credentialRef = metadata["credential_ref"],
                managedByBkx = managed,
                createdAt = createdAt,
                updatedAt = updatedAt,
                retention = retention,
            )
        }.getOrNull()
    }
}
