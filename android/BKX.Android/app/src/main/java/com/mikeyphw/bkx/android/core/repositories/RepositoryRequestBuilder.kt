package com.mikeyphw.bkx.android.core.repositories

import com.mikeyphw.bkx.android.core.backup.BackupEngineKind
import com.mikeyphw.bkx.android.core.bridge.BridgeRequestEnvelope
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object RepositoryRequestBuilder {
    const val OPERATION_ADD = "repositories.add"
    const val OPERATION_LIST = "repositories.list"
    const val OPERATION_SHOW = "repositories.show"
    const val OPERATION_REMOVE = "repositories.remove"
    const val OPERATION_INIT = "repository.init"
    const val OPERATION_CHECK = "repository.check"
    const val OPERATION_PRUNE = "repository.prune"
    const val OPERATION_SNAPSHOT = "backup.snapshot"
    const val OPERATION_SNAPSHOTS_LIST = "snapshots.list"
    const val OPERATION_SNAPSHOTS_BROWSE = "snapshots.browse"
    const val OPERATION_SNAPSHOTS_SHOW = "snapshots.show"
    const val OPERATION_SNAPSHOTS_DIFF = "snapshots.diff"
    const val OPERATION_SNAPSHOTS_RESTORE = "snapshots.restore"
    const val OPERATION_SNAPSHOTS_FORGET = "snapshots.forget"
    const val OPERATION_RETENTION_SET = "retention.set"
    const val OPERATION_RETENTION_SHOW = "retention.show"
    const val OPERATION_RETENTION_CLEAR = "retention.clear"
    const val OPERATION_RETENTION_APPLY = "retention.apply"

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

    fun list(requestId: String): BridgeRequestEnvelope = BridgeRequestEnvelope(requestId = requestId, operation = OPERATION_LIST)
    fun show(requestId: String, repositoryId: String): BridgeRequestEnvelope = repositoryIdRequest(requestId, OPERATION_SHOW, repositoryId)
    fun remove(requestId: String, repositoryId: String): BridgeRequestEnvelope = repositoryIdRequest(requestId, OPERATION_REMOVE, repositoryId)

    fun initialize(requestId: String, repository: RepositoryDefinition): BridgeRequestEnvelope = repositoryRefRequest(requestId, OPERATION_INIT, repository)
    fun check(requestId: String, repository: RepositoryDefinition, readData: Boolean = false, readDataSubset: String? = null): BridgeRequestEnvelope = BridgeRequestEnvelope(
        requestId = requestId,
        operation = OPERATION_CHECK,
        payload = repositoryPayload(repository) {
            put("read_data", readData)
            readDataSubset?.takeIf { it.isNotBlank() }?.let { put("read_data_subset", it) }
        },
    )

    fun prune(requestId: String, repository: RepositoryDefinition, dryRun: Boolean = true, confirm: Boolean = false, maxUnused: String? = null, maxRepackSize: String? = null): BridgeRequestEnvelope = BridgeRequestEnvelope(
        requestId = requestId,
        operation = OPERATION_PRUNE,
        payload = repositoryPayload(repository) {
            put("dry_run", dryRun)
            put("confirm", confirm)
            maxUnused?.takeIf { it.isNotBlank() }?.let { put("max_unused", it) }
            maxRepackSize?.takeIf { it.isNotBlank() }?.let { put("max_repack_size", it) }
        },
    )

    fun snapshot(requestId: String, repository: RepositoryDefinition, sources: List<String>, tags: List<String> = emptyList(), excludes: List<String> = emptyList(), verify: Boolean = true): BridgeRequestEnvelope {
        require(sources.isNotEmpty() && sources.none { it.isBlank() }) { "snapshot sources must not be blank" }
        return BridgeRequestEnvelope(requestId = requestId, operation = OPERATION_SNAPSHOT, payload = repositoryPayload(repository) {
            put("engine", BackupEngineKind.Restic.wireValue)
            put("sources", strings(sources)); put("tags", strings(tags)); put("excludes", strings(excludes)); put("verify", verify)
        })
    }

    fun snapshots(requestId: String, repository: RepositoryDefinition, host: String? = null, path: String? = null, tags: List<String> = emptyList()): BridgeRequestEnvelope = BridgeRequestEnvelope(
        requestId = requestId, operation = OPERATION_SNAPSHOTS_LIST, payload = repositoryPayload(repository) {
            host?.takeIf { it.isNotBlank() }?.let { put("host", it) }
            path?.takeIf { it.isNotBlank() }?.let { put("path", it) }
            if (tags.isNotEmpty()) put("tags", strings(tags))
        },
    )

    fun snapshotBrowse(requestId: String, repository: RepositoryDefinition, snapshot: String, path: String = "/", recursive: Boolean = false): BridgeRequestEnvelope {
        val normalizedPath = path.trim().ifBlank { "/" }
        require(normalizedPath.startsWith("/")) { "snapshot browse path must be absolute within the snapshot" }
        return BridgeRequestEnvelope(
            requestId = requestId, operation = OPERATION_SNAPSHOTS_BROWSE, payload = repositoryPayload(repository) {
                put("snapshot", requireSnapshot(snapshot)); put("path", normalizedPath); put("recursive", recursive)
            },
        )
    }

    fun snapshotShow(requestId: String, repository: RepositoryDefinition, snapshot: String): BridgeRequestEnvelope = BridgeRequestEnvelope(
        requestId = requestId, operation = OPERATION_SNAPSHOTS_SHOW, payload = repositoryPayload(repository) { put("snapshot", requireSnapshot(snapshot)) },
    )

    fun snapshotDiff(requestId: String, repository: RepositoryDefinition, source: String, target: String, metadata: Boolean = false): BridgeRequestEnvelope = BridgeRequestEnvelope(
        requestId = requestId, operation = OPERATION_SNAPSHOTS_DIFF, payload = repositoryPayload(repository) {
            put("source_snapshot", requireSnapshot(source)); put("target_snapshot", requireSnapshot(target)); put("metadata", metadata)
        },
    )

    fun snapshotRestore(requestId: String, repository: RepositoryDefinition, snapshot: String, target: String, includes: List<String> = emptyList(), excludes: List<String> = emptyList(), overwrite: SnapshotOverwriteMode = SnapshotOverwriteMode.Never, dryRun: Boolean = false): BridgeRequestEnvelope {
        require(target.isNotBlank()) { "restore target must not be blank" }
        require(includes.isEmpty() || excludes.isEmpty()) { "restore include and exclude filters are mutually exclusive" }
        return BridgeRequestEnvelope(requestId = requestId, operation = OPERATION_SNAPSHOTS_RESTORE, payload = repositoryPayload(repository) {
            put("snapshot", requireSnapshot(snapshot)); put("target", target); put("includes", strings(includes)); put("excludes", strings(excludes)); put("overwrite", overwrite.wireValue); put("dry_run", dryRun)
        })
    }

    fun snapshotForget(requestId: String, repository: RepositoryDefinition, snapshotId: String, confirm: Boolean): BridgeRequestEnvelope {
        require(snapshotId.trim() != "latest") { "forget requires an explicit snapshot id" }
        return BridgeRequestEnvelope(requestId = requestId, operation = OPERATION_SNAPSHOTS_FORGET, payload = repositoryPayload(repository) {
            put("snapshot", requireSnapshot(snapshotId)); put("confirm", confirm)
        })
    }

    fun retentionSet(requestId: String, repositoryId: String, policy: RetentionPolicy): BridgeRequestEnvelope = BridgeRequestEnvelope(
        requestId = requestId,
        operation = OPERATION_RETENTION_SET,
        payload = buildJsonObject {
            put("repository_id", requireRepositoryIdValue(repositoryId))
            put("policy", buildJsonObject {
                put("schema_version", policy.schemaVersion)
                policy.keepLast?.let { put("keep_last", it) }
                policy.keepHourly?.let { put("keep_hourly", it) }
                policy.keepDaily?.let { put("keep_daily", it) }
                policy.keepWeekly?.let { put("keep_weekly", it) }
                policy.keepMonthly?.let { put("keep_monthly", it) }
                policy.keepYearly?.let { put("keep_yearly", it) }
                policy.keepWithin?.let { put("keep_within", it) }
                if (policy.keepTags.isNotEmpty()) put("keep_tags", strings(policy.keepTags))
                if (policy.groupBy.isNotBlank()) put("group_by", policy.groupBy)
            })
        },
    )
    fun retentionShow(requestId: String, repositoryId: String): BridgeRequestEnvelope = repositoryIdRequest(requestId, OPERATION_RETENTION_SHOW, repositoryId)
    fun retentionClear(requestId: String, repositoryId: String): BridgeRequestEnvelope = repositoryIdRequest(requestId, OPERATION_RETENTION_CLEAR, repositoryId)
    fun retentionApply(requestId: String, repositoryId: String, dryRun: Boolean = true, confirm: Boolean = false): BridgeRequestEnvelope = BridgeRequestEnvelope(
        requestId = requestId, operation = OPERATION_RETENTION_APPLY, payload = buildJsonObject { put("repository_id", requireRepositoryIdValue(repositoryId)); put("dry_run", dryRun); put("confirm", confirm) },
    )
    fun retentionApply(requestId: String, repository: RepositoryDefinition, dryRun: Boolean = true, confirm: Boolean = false): BridgeRequestEnvelope = BridgeRequestEnvelope(
        requestId = requestId,
        operation = OPERATION_RETENTION_APPLY,
        payload = buildJsonObject {
            put("repository_id", requireRepositoryIdValue(repository.repositoryId))
            put("repository_backend", repository.backend)
            put("dry_run", dryRun)
            put("confirm", confirm)
        },
    )

    private fun repositoryRefRequest(requestId: String, operation: String, repository: RepositoryDefinition) = BridgeRequestEnvelope(requestId = requestId, operation = operation, payload = repositoryPayload(repository) {})
    private fun repositoryIdRequest(requestId: String, operation: String, repositoryId: String) = BridgeRequestEnvelope(requestId = requestId, operation = operation, payload = buildJsonObject { put("repository_id", requireRepositoryIdValue(repositoryId)) })
    private fun repositoryPayload(repository: RepositoryDefinition, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("repository_ref", requireRepositoryIdValue(repository.repositoryId))
        put("repository_backend", repository.backend)
        extra()
    }
    private fun strings(values: List<String>) = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }
    private fun requireSnapshot(value: String): String = value.trim().also { require(it.isNotBlank()) { "snapshot selector must not be blank" } }
    private fun requireRepositoryIdValue(value: String): String = value.trim().also { require(it.matches(Regex("^[a-z0-9][a-z0-9._-]{0,127}$"))) { "invalid repository id" } }
}
