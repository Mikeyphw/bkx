package com.mikeyphw.bkx.android.core.chrootctl

import com.mikeyphw.bkx.android.core.backup.BackupDestinationSelection
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Android-side orchestration model for one native chrootctl export. */
data class ChrootctlExportPlan(
    val chrootName: String,
    val destination: BackupDestinationSelection,
    val overwriteExisting: Boolean = false,
) {
    init {
        require(chrootName.isNotBlank()) { "Chroot name must not be blank." }
        require(chrootName.length <= 128) { "Chroot name is too long." }
    }

    fun fingerprint(): String = listOf(
        chrootName.trim(),
        destination.destinationId,
        destination.stagedDirectoryPath,
        overwriteExisting.toString(),
    ).joinToString("\u001e").stableToken()
}

data class ChrootctlPreviewBlocker(
    val code: String,
    val message: String,
) {
    init {
        require(code.isNotBlank()) { "Preview blocker code must not be blank." }
        require(message.isNotBlank()) { "Preview blocker message must not be blank." }
    }
}

data class ChrootctlExportPreview(
    val requestId: String,
    val planFingerprint: String,
    val contract: ChrootctlPreviewContract,
    val managedArchivePath: String,
    val sourceSizeBytes: Long,
    val chrootctlRequiredBytes: Long,
    val chrootctlFreeBytes: Long,
    val bkxStagingFreeBytes: Long,
    val blockers: List<ChrootctlPreviewBlocker>,
    val warnings: List<String>,
) {
    val isExecutable: Boolean get() = blockers.isEmpty()
}

data class ChrootctlExportArtifact(
    val chrootName: String,
    val archivePath: String,
    val arch: String,
    val checksumSha256: String,
    val checksumPath: String?,
    val encrypted: Boolean,
) {
    init {
        require(chrootName.isNotBlank()) { "Export result chroot name must not be blank." }
        require(archivePath.startsWith('/')) { "chrootctl export artifact must be a managed absolute path." }
        require(SHA256.matches(checksumSha256)) { "chrootctl export checksum must be SHA-256." }
    }

    companion object {
        private val SHA256 = Regex("[a-fA-F0-9]{64}")
    }
}

data class ChrootctlStagedArtifact(
    val sourceManagedPath: String,
    val stagedPath: String,
    val fileName: String,
    val sizeBytes: Long,
    val checksumSha256: String,
) {
    init {
        require(sourceManagedPath.startsWith('/')) { "Managed source path must be absolute." }
        require(stagedPath.startsWith('/')) { "BKX staged export path must be absolute." }
        require(fileName.isNotBlank() && '/' !in fileName && '\\' !in fileName) { "Invalid staged export filename." }
        require(sizeBytes > 0) { "Staged export must not be empty." }
        require(checksumSha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Staged export checksum must be SHA-256." }
    }
}

enum class ChrootctlExportPhase {
    Preview,
    Exporting,
    Handoff,
    TransferPreview,
    Publishing,
    Complete,
}

data class ChrootctlExportProgress(
    val phase: ChrootctlExportPhase,
    val percent: Int? = null,
    val message: String,
) {
    init {
        require(percent == null || percent in 0..100) { "Export progress must be within 0..100." }
        require(message.isNotBlank()) { "Export progress message must not be blank." }
    }
}

data class ChrootctlExportPublication(
    val chrootName: String,
    val destinationLocation: String,
    val checksumSha256: String,
    val encrypted: Boolean,
    val retainedChrootctlArtifactPath: String,
    val retainedBkxStagingPath: String? = null,
) {
    init {
        require(chrootName.isNotBlank())
        require(destinationLocation.isNotBlank())
        require(checksumSha256.matches(Regex("[a-fA-F0-9]{64}")))
        require(retainedChrootctlArtifactPath.startsWith('/'))
        require(retainedBkxStagingPath == null || retainedBkxStagingPath.startsWith('/'))
    }
}

fun ChrootctlBridgeResponse.toChrootctlExportPreview(
    plan: ChrootctlExportPlan,
    bkxStagingFreeBytes: Long,
    bkxStagingReserveBytes: Long,
): ChrootctlExportPreview {
    require(ok) { error?.message ?: "chrootctl export preview failed." }
    val dataObject = data as? JsonObject
        ?: throw ChrootctlBridgeProtocolException("chrootctl export preview data must be an object.")
    val managedArchive = dataObject.string("archive")
        ?: throw ChrootctlBridgeProtocolException("chrootctl export preview did not return a managed archive path.")
    if (!managedArchive.startsWith('/')) {
        throw ChrootctlBridgeProtocolException("BKX chroot export preview unexpectedly selected a remote chrootctl destination.")
    }
    val required = dataObject.long("required_bytes") ?: dataObject.long("size_bytes") ?: 0L
    val blockers = dataObject.blockers().toMutableList()
    if (dataObject.bool("ok") == false && blockers.isEmpty()) {
        blockers += ChrootctlPreviewBlocker(
            code = "chrootctl_preview_not_executable",
            message = "chrootctl reported that this export preview is not executable.",
        )
    }
    val stagingRequired = required.coerceAtLeast(1L) + bkxStagingReserveBytes.coerceAtLeast(0L)
    if (bkxStagingFreeBytes < stagingRequired) {
        blockers += ChrootctlPreviewBlocker(
            code = "bkx_staging_space",
            message = "BKX staging needs at least $stagingRequired bytes free before the chroot export can be handed off safely.",
        )
    }
    if (dataObject.bool("remote") == true || dataObject.bool("streaming") == true) {
        blockers += ChrootctlPreviewBlocker(
            code = "unexpected_chrootctl_remote",
            message = "BKX-owned destinations require a local managed chrootctl artifact before publication.",
        )
    }
    return ChrootctlExportPreview(
        requestId = requestId,
        planFingerprint = plan.fingerprint(),
        contract = ChrootctlBridgeCodec.previewContract(this),
        managedArchivePath = managedArchive,
        sourceSizeBytes = dataObject.long("size_bytes") ?: 0L,
        chrootctlRequiredBytes = required,
        chrootctlFreeBytes = dataObject.long("free_bytes") ?: 0L,
        bkxStagingFreeBytes = bkxStagingFreeBytes,
        blockers = blockers.distinctBy { it.code to it.message },
        warnings = dataObject.strings("warnings"),
    )
}

fun ChrootctlOperationEvent.exportArtifactOrNull(): ChrootctlExportArtifact? {
    if (type != "operation_succeeded") return null
    val result = payload["result"] as? JsonObject ?: return null
    return result.toExportArtifact()
}

/** Handles operation/reconcile responses whose data is a persisted operation record. */
fun ChrootctlBridgeResponse.reconciledExportArtifactOrNull(): ChrootctlExportArtifact? {
    if (!ok) return null
    val record = data as? JsonObject ?: return null
    if (!record.string("state").equals("succeeded", ignoreCase = true)) return null
    val result = (record["resultPayload"] ?: record["result_payload"]) as? JsonObject ?: return null
    return result.toExportArtifact()
}

private fun JsonObject.toExportArtifact(): ChrootctlExportArtifact {
    val archive = string("archive")
        ?: throw ChrootctlBridgeProtocolException("Successful chrootctl export result is missing archive.")
    val checksum = string("checksum")
        ?: throw ChrootctlBridgeProtocolException("Successful chrootctl export result is missing checksum.")
    return ChrootctlExportArtifact(
        chrootName = string("name")
            ?: throw ChrootctlBridgeProtocolException("Successful chrootctl export result is missing name."),
        archivePath = archive,
        arch = string("arch").orEmpty(),
        checksumSha256 = checksum.lowercase(),
        checksumPath = string("checksum_path") ?: string("checksumPath"),
        encrypted = bool("encrypted") ?: false,
    )
}

private fun JsonObject.blockers(): List<ChrootctlPreviewBlocker> {
    val rows = this["blocking"] as? JsonArray ?: return emptyList()
    return rows.mapNotNull { value ->
        val row = value as? JsonObject ?: return@mapNotNull null
        val code = row.string("code") ?: return@mapNotNull null
        val message = row.string("message") ?: return@mapNotNull null
        ChrootctlPreviewBlocker(code, message)
    }
}

private fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray)
    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
    .orEmpty()

private fun JsonObject.string(key: String): String? = when (val value = this[key]) {
    null, JsonNull -> null
    is JsonPrimitive -> value.contentOrNull?.takeIf(String::isNotBlank)
    else -> null
}

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

private fun String.stableToken(): String {
    var hash = 1125899906842597L
    for (char in this) hash = 31 * hash + char.code
    return hash.toULong().toString(16).padStart(16, '0').repeat(4).take(64)
}
