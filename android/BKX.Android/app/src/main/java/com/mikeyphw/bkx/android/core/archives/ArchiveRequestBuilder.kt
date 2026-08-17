package com.mikeyphw.bkx.android.core.archives

import com.mikeyphw.bkx.android.core.bridge.BridgeRequestEnvelope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class ArchiveRequestBuilder {
    fun buildListRequest(requestId: String): BridgeRequestEnvelope = BridgeRequestEnvelope(
        requestId = requestId,
        operation = ArchiveReadOnlyOperation.List.operation,
        payload = readOnlyPayload(),
    )

    fun buildInfoRequest(requestId: String, archive: ArchiveLocation): BridgeRequestEnvelope = BridgeRequestEnvelope(
        requestId = requestId,
        operation = ArchiveReadOnlyOperation.Info.operation,
        payload = readOnlyPayload(archive),
    )

    fun buildBrowseRequest(requestId: String, page: ArchiveBrowsePageRequest): BridgeRequestEnvelope = BridgeRequestEnvelope(
        requestId = requestId,
        operation = ArchiveReadOnlyOperation.Browse.operation,
        payload = readOnlyPayload(page.archive) {
            putJsonObject("page") {
                page.pageToken?.let { put("page_token", it) }
                put("page_size", page.pageSize)
            }
        },
    )

    fun buildVerifyRequest(requestId: String, archive: ArchiveLocation): BridgeRequestEnvelope = BridgeRequestEnvelope(
        requestId = requestId,
        operation = ArchiveReadOnlyOperation.Verify.operation,
        payload = readOnlyPayload(archive),
    )

    fun requireReadOnlyOperation(operation: String) {
        require(ArchiveReadOnlyOperation.entries.any { it.operation == operation }) {
            "Archive screen supports only read-only archive workflows; rejected $operation."
        }
    }

    private fun readOnlyPayload(
        archive: ArchiveLocation? = null,
        extra: JsonObjectBuilder.() -> Unit = {},
    ): JsonObject = buildJsonObject {
        put("read_only", true)
        archive?.let {
            putJsonObject("archive") {
                put("archive_id", it.archiveId)
                put("display_name", it.displayName)
                put("staged_path", it.stagedPath)
            }
        }
        extra()
    }
}
