package com.mikeyphw.bkx.android.core.transfer

import com.mikeyphw.bkx.android.core.bridge.BridgeRequestEnvelope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class TransferRequestBuilder {
    fun buildPreviewRequest(requestId: String, draft: TransferTopologyDraft): BridgeRequestEnvelope {
        draft.requireValid()
        return BridgeRequestEnvelope(
            requestId = requestId,
            operation = OPERATION_TRANSFER_PREVIEW,
            payload = draft.toTransferPayload(dryRun = true),
        )
    }

    fun buildRunRequest(
        requestId: String,
        draft: TransferTopologyDraft,
        preview: TransferPreviewSummary?,
    ): BridgeRequestEnvelope {
        draft.requireValid()
        require(preview != null) { "transfer.run requires a successful transfer.plan topology preview first." }
        require(preview.draftFingerprint == draft.fingerprint()) {
            "transfer.run preview does not match the current topology, mapping, policy, or verification plan."
        }
        require(preview.isExecutable) { "transfer.run is blocked by collision, overlap, or missing authoritative plan token." }
        require(preview.planToken.isNotBlank()) { "transfer.run requires the plan_token from the matching transfer.plan artifact." }
        return BridgeRequestEnvelope(
            requestId = requestId,
            operation = OPERATION_TRANSFER_RUN,
            payload = draft.toTransferPayload(
                dryRun = false,
                extra = buildJsonObject {
                    put("plan_request_id", preview.requestId)
                    put("preview_request_id", preview.requestId)
                    put("plan_token", preview.planToken)
                    put("topology_label", preview.topologyLabel)
                },
            ),
        )
    }

    companion object {
        const val OPERATION_TRANSFER_PREVIEW = "transfer.plan"
        const val OPERATION_TRANSFER_RUN = "transfer.run"
    }
}

fun TransferTopologyDraft.toTransferPayload(
    dryRun: Boolean,
    extra: JsonObject = buildJsonObject { },
): JsonObject {
    requireValid()
    val safeDestination = requireNotNull(destination)
    return buildJsonObject {
        put("mode", mode.protocolValue)
        put("direction", when (mode) {
            TransferMode.Push -> "push"
            TransferMode.Pull -> "pull"
            TransferMode.Sync -> "sync"
        })
        putJsonArray("sources") {
            sources.forEach { source ->
                add(buildJsonObject {
                    put("source_id", source.endpointId)
                    put("label", source.label)
                    put("location", source.location)
                    put("remote", source.isRemote)
                })
            }
        }
        putJsonObject("destination") {
            put("destination_id", safeDestination.endpointId)
            put("label", safeDestination.label)
            put("location", safeDestination.location)
            put("remote", safeDestination.isRemote)
        }
        put("verify", verifyAfterTransfer)
        put("checksum", checksum)
        put("dry_run", dryRun)
        put("overwrite", overwriteExisting)
        put("delete_extraneous_on_sync", deleteExtraneousOnSync)
        if (mode == TransferMode.Sync) {
            put("sync_delete_limit", syncDeleteLimit)
        }
        putJsonObject("policies") {
            put("symlink", symlinkPolicy.protocolValue)
            put("special_file", specialFilePolicy.protocolValue)
        }
        putJsonObject("safety") {
            put("requires_topology_preview", true)
            put("collision_summary_source", "bkx_result_fields")
            put("verification_failure_state", "typed_failure")
            put("partial_failure_state", "typed_failure")
        }
        for ((key, value) in extra) {
            put(key, value)
        }
    }
}
