package com.mikeyphw.bkx.android.core.parity

import com.mikeyphw.bkx.android.core.archives.ArchiveLocation
import com.mikeyphw.bkx.android.core.backup.BackupDestinationSelection
import com.mikeyphw.bkx.android.core.backup.BackupPlanDraft
import com.mikeyphw.bkx.android.core.backup.BackupRequestBuilder
import com.mikeyphw.bkx.android.core.backup.BackupSourceSelection
import com.mikeyphw.bkx.android.core.bridge.BridgeRequestEnvelope
import com.mikeyphw.bkx.android.core.rendering.BkxBridgeResponseEnvelope
import com.mikeyphw.bkx.android.core.rendering.BkxBridgeVerification
import com.mikeyphw.bkx.android.core.rendering.BkxBridgeVerificationStatus
import com.mikeyphw.bkx.android.core.restore.RestoreDestinationSelection
import com.mikeyphw.bkx.android.core.restore.RestoreDraft
import com.mikeyphw.bkx.android.core.restore.RestoreRequestBuilder
import com.mikeyphw.bkx.android.core.transfer.TransferEndpoint
import com.mikeyphw.bkx.android.core.transfer.TransferMode
import com.mikeyphw.bkx.android.core.transfer.TransferRequestBuilder
import com.mikeyphw.bkx.android.core.transfer.TransferTopologyDraft
import kotlinx.serialization.json.JsonObject

object CliParitySnapshots {
    fun representativeAndroidRequests(): List<BridgeRequestEnvelope> = listOf(
        BackupRequestBuilder().buildPlanRequest(
            requestId = "parity-backup-plan",
            draft = BackupPlanDraft(
                source = BackupSourceSelection("src", "Source", "/shared/source"),
                destination = BackupDestinationSelection("dst", "Destination", "/shared/destination"),
            ),
        ),
        RestoreRequestBuilder().buildPreviewRequest(
            requestId = "parity-restore-preview",
            draft = RestoreDraft(
                archive = ArchiveLocation("archive", "backup.bkx", "/shared/backup.bkx"),
                destination = RestoreDestinationSelection("dst", "Fresh restore", "/shared/restore"),
            ),
        ),
        TransferRequestBuilder().buildPreviewRequest(
            requestId = "parity-transfer-plan",
            draft = TransferTopologyDraft(
                mode = TransferMode.Push,
                sources = listOf(TransferEndpoint("src", "Archive", "/shared/backup.bkx")),
                destination = TransferEndpoint("dst", "Remote", "user@example:/backups", isRemote = true),
            ),
        ),
    )


    fun representativeCliJsonResults(): List<BkxBridgeResponseEnvelope> = listOf(
        BkxBridgeResponseEnvelope(
            protocolVersion = 1,
            requestId = "parity-backup-plan",
            ok = true,
            operation = "backup.plan",
            verification = BkxBridgeVerification(BkxBridgeVerificationStatus.NotApplicable),
            completedAtMillis = 1_786_000_000_000,
        ),
        BkxBridgeResponseEnvelope(
            protocolVersion = 1,
            requestId = "parity-restore-preview",
            ok = true,
            operation = "restore.preview",
            verification = BkxBridgeVerification(BkxBridgeVerificationStatus.Passed),
            completedAtMillis = 1_786_000_000_001,
        ),
        BkxBridgeResponseEnvelope(
            protocolVersion = 1,
            requestId = "parity-transfer-plan",
            ok = true,
            operation = "transfer.plan",
            verification = BkxBridgeVerification(BkxBridgeVerificationStatus.NotApplicable),
            completedAtMillis = 1_786_000_000_002,
        ),
    )

    fun semanticResultSnapshot(response: BkxBridgeResponseEnvelope): CliResultSemanticSnapshot = CliResultSemanticSnapshot(
        operation = response.operation,
        requestId = response.requestId,
        ok = response.ok,
        verificationStatus = response.verification.status.name,
        errorCodes = response.errors.map { it.code }.sorted(),
    )

    fun semanticSnapshot(request: BridgeRequestEnvelope): CliSemanticSnapshot = CliSemanticSnapshot(
        operation = request.operation,
        requestId = request.requestId,
        payloadKeys = request.payload.keys.sorted(),
        outputMode = (request.payload["output_mode"] ?: request.payload["outputMode"])?.toString()?.trim('"') ?: "json",
    )
}

data class CliSemanticSnapshot(
    val operation: String,
    val requestId: String,
    val payloadKeys: List<String>,
    val outputMode: String,
) {
    init {
        require(operation.isNotBlank()) { "CLI parity operation must not be blank" }
        require(requestId.isNotBlank()) { "CLI parity request id must not be blank" }
        require(outputMode == "json") { "Android bridge requests must use JSON output mode for CLI parity" }
    }

    fun hasSemanticPayloadKey(key: String): Boolean = payloadKeys.contains(key)
}

fun JsonObject.keysSorted(): List<String> = keys.sorted()


data class CliResultSemanticSnapshot(
    val operation: String,
    val requestId: String,
    val ok: Boolean,
    val verificationStatus: String,
    val errorCodes: List<String>,
) {
    init {
        require(operation.isNotBlank()) { "CLI parity result operation must not be blank" }
        require(requestId.isNotBlank()) { "CLI parity result request id must not be blank" }
        require(verificationStatus.isNotBlank()) { "CLI parity verification status must not be blank" }
    }
}
