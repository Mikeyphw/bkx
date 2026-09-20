package com.mikeyphw.bkx.android.core.backup

import com.mikeyphw.bkx.android.core.bridge.BridgeRequestEnvelope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class BackupRequestBuilder {
    fun buildPlanRequest(requestId: String, draft: BackupPlanDraft): BridgeRequestEnvelope {
        draft.requireValid()
        require(draft.engine != BackupEngineKind.Chrootctl) { "chrootctl backups are orchestrated by ChrootctlExportCoordinator, not the Go backup bridge." }
        return BridgeRequestEnvelope(
            requestId = requestId,
            operation = OPERATION_BACKUP_PLAN,
            payload = draft.toPlanPayload(),
        )
    }

    fun buildCreateRequest(
        requestId: String,
        draft: BackupPlanDraft,
        preview: BackupPlanPreview?,
    ): BridgeRequestEnvelope {
        draft.requireValid()
        require(draft.engine != BackupEngineKind.Chrootctl) { "chrootctl backups are orchestrated by ChrootctlExportCoordinator, not the Go backup bridge." }
        require(preview != null) { "backup.create requires a successful backup.plan preview first." }
        require(preview.draftFingerprint == draft.fingerprint()) {
            "backup.create preview does not match the current draft."
        }
        require(preview.isExecutable) { "backup.create is blocked by the plan preview." }
        return BridgeRequestEnvelope(
            requestId = requestId,
            operation = OPERATION_BACKUP_CREATE,
            payload = draft.toPlanPayload(
                extra = buildJsonObject {
                    put("plan_request_id", preview.requestId)
                    put("plan_token", preview.planToken)
                },
            ),
        )
    }

    companion object {
        const val OPERATION_BACKUP_PLAN = "backup.plan"
        const val OPERATION_BACKUP_CREATE = "backup.create"
    }
}

fun BackupPlanDraft.toPlanPayload(extra: JsonObject = buildJsonObject { }): JsonObject {
    requireValid()
    require(engine != BackupEngineKind.Chrootctl) { "chrootctl backup plans must not be serialized as filesystem backup payloads." }
    val safeSource = requireNotNull(source)
    val safeSourcePath = requireNotNull(safeSource.filesystemPath) { "Go backup payload requires a filesystem source." }
    val safeDestination = requireNotNull(destination)
    return buildJsonObject {
        put("operation_mode", "plan_then_create")
        putJsonObject("source") {
            put("source_id", safeSource.sourceId)
            put("label", safeSource.label)
            put("staged_path", safeSourcePath)
            safeSource.originalUri?.let { put("original_uri", it) }
        }
        putJsonObject("destination") {
            put("destination_id", safeDestination.destinationId)
            put("label", safeDestination.label)
            put("staged_directory_path", safeDestination.stagedDirectoryPath)
        }
        putJsonObject("filters") {
            put("include_patterns", buildJsonArray { filters.includePatterns.forEach { add(it) } })
            put("exclude_patterns", buildJsonArray { filters.excludePatterns.forEach { add(it) } })
            put("include_hidden", filters.includeHidden)
            put("follow_symlinks", filters.followSymlinks)
        }
        putJsonObject("archive") {
            normalizedArchiveName()?.let { put("archive_name", it) }
            put("compression", compression.protocolValue)
            putJsonObject("split") {
                put("enabled", split.enabled)
                split.chunkSizeBytes?.let { put("chunk_size_bytes", it) }
            }
            putJsonObject("verification") {
                put("verify_after_create", verification.verifyAfterCreate)
                put("expected_hash_algorithm", verification.expectedHashAlgorithm)
            }
        }
        for ((key, value) in extra) {
            put(key, value)
        }
    }
}
