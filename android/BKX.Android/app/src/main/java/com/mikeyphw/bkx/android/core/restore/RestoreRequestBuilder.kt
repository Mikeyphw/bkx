package com.mikeyphw.bkx.android.core.restore

import com.mikeyphw.bkx.android.core.bridge.BridgeRequestEnvelope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class RestoreRequestBuilder {
    fun buildPreviewRequest(requestId: String, draft: RestoreDraft): BridgeRequestEnvelope {
        draft.requireValid()
        return BridgeRequestEnvelope(
            requestId = requestId,
            operation = OPERATION_RESTORE_PREVIEW,
            payload = draft.toRestorePayload(dryRun = true, overwrite = false, confirmOverwrite = false),
        )
    }

    fun buildExtractRequest(
        requestId: String,
        draft: RestoreDraft,
        preview: RestorePreviewSummary?,
        confirmation: RestoreExtractionConfirmation?,
    ): BridgeRequestEnvelope {
        draft.requireValid()
        require(preview != null) { "restore.extract requires a successful restore.preview first." }
        require(preview.draftFingerprint == draft.fingerprint()) {
            "restore.extract preview does not match the current archive, destination, subset, or output mode."
        }
        require(preview.isExecutable) { "restore.extract is blocked by BKX path-safety/link/special-file preview fields." }
        val destructive = preview.requiresDestructiveConfirmation
        if (destructive) {
            require(confirmation != null && confirmation.matches(draft, preview)) {
                "restore.extract requires explicit confirmation for destructive overwrite preview fields."
            }
        }
        return BridgeRequestEnvelope(
            requestId = requestId,
            operation = OPERATION_RESTORE_EXTRACT,
            payload = draft.toRestorePayload(
                dryRun = false,
                overwrite = destructive,
                confirmOverwrite = destructive,
                extra = buildJsonObject {
                    put("preview_request_id", preview.requestId)
                    put("plan_token", preview.planToken)
                    put("destructive_confirmation_required", destructive)
                    if (destructive) {
                        val matchingConfirmation = requireNotNull(confirmation)
                        putJsonObject("confirmation_evidence") {
                            put("phrase", matchingConfirmation.confirmationPhrase)
                            put("confirmed_at_ms", matchingConfirmation.confirmedAtEpochMillis)
                            put("acknowledged_overwrite_count", matchingConfirmation.acknowledgedOverwriteCount)
                            put("draft_fingerprint", matchingConfirmation.draftFingerprint)
                            put("plan_token", matchingConfirmation.planToken)
                        }
                    }
                },
            ),
        )
    }

    companion object {
        const val OPERATION_RESTORE_PREVIEW = "restore.preview"
        const val OPERATION_RESTORE_EXTRACT = "restore.extract"
    }
}

fun RestoreDraft.toRestorePayload(
    dryRun: Boolean,
    overwrite: Boolean,
    confirmOverwrite: Boolean,
    extra: JsonObject = buildJsonObject { },
): JsonObject {
    requireValid()
    val selectedArchive = requireNotNull(archive)
    return buildJsonObject {
        put("archive", selectedArchive.stagedPath)
        put("archive_id", selectedArchive.archiveId)
        put("archive_label", selectedArchive.displayName)
        destination?.let { destination ->
            putJsonObject("destination") {
                put("destination_id", destination.destinationId)
                put("label", destination.label)
                put("staged_directory_path", destination.stagedDirectoryPath)
            }
        }
        put("destination_path", destination?.stagedDirectoryPath)
        put("output_mode", outputMode.protocolValue)
        put("fresh_dir", outputMode == RestoreOutputMode.FreshDirectory)
        put("verify", verifyBeforeRestore)
        put("dry_run", dryRun)
        put("overwrite", overwrite)
        put("confirm_overwrite", confirmOverwrite)
        put("paths", buildJsonArray { subset.selectedPaths.forEach { add(it) } })
        putJsonObject("safety") {
            put("requires_preview", true)
            put("requires_destructive_confirmation", true)
            put("path_safety_summary_source", "bkx_result_fields")
        }
        for ((key, value) in extra) {
            put(key, value)
        }
    }
}
