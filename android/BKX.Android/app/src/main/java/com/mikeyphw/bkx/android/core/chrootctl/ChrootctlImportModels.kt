package com.mikeyphw.bkx.android.core.chrootctl

import kotlinx.serialization.json.*

data class ChrootctlImportPlan(
    val sourceLocation: String,
    val requestedName: String? = null,
    val conflict: String = "rename",
    val allowLegacyMetadata: Boolean = false,
    val importStrategy: String = "auto",
) {
    init {
        require(sourceLocation.isNotBlank())
        require(conflict in setOf("rename", "overwrite"))
        require(importStrategy in setOf("auto", "staged", "streaming"))
        require(requestedName == null || requestedName.isNotBlank())
    }
    fun fingerprint() = listOf(sourceLocation, requestedName.orEmpty(), conflict, allowLegacyMetadata, importStrategy).joinToString("\u001e").stableImportToken()
}

data class ChrootctlImportInspection(
    val suggestedName: String,
    val distro: String,
    val arch: String,
    val release: String,
    val version: String,
    val sourceBytes: Long,
    val expandedBytes: Long,
    val metadataFidelity: String,
    val checksumVerified: Boolean?,
)

data class ChrootctlImportPreview(
    val requestId: String,
    val planFingerprint: String,
    val contract: ChrootctlPreviewContract,
    val stagedArchivePath: String,
    val stagingId: String,
    val stagedChecksumSha256: String,
    val inspection: ChrootctlImportInspection,
    val blockers: List<ChrootctlPreviewBlocker>,
    val warnings: List<String>,
) {
    val requiresLegacyOptIn get() = inspection.metadataFidelity == "legacy_unverified" && blockers.any { it.code == "legacy_metadata_unverified" }
    val isExecutable get() = blockers.isEmpty()
}

data class ChrootctlStagedImport(val archivePath: String, val checksumSha256: String, val sizeBytes: Long, val stagingId: String) {
    init { require(archivePath.startsWith('/')); require(Regex("[a-fA-F0-9]{64}").matches(checksumSha256)); require(sizeBytes > 0); require(Regex("[a-f0-9]{32}").matches(stagingId)) }
}

data class ChrootctlImportResult(val name: String, val path: String, val metadataFidelity: String, val operationId: String?, val retainedStagingId: String? = null)

enum class ChrootctlImportPhase { Acquiring, RootHandoff, Inspecting, Importing, Complete }
data class ChrootctlImportProgress(val phase: ChrootctlImportPhase, val percent: Int? = null, val message: String)

fun ChrootctlBridgeResponse.toChrootctlImportPreview(plan: ChrootctlImportPlan, staged: ChrootctlStagedImport): ChrootctlImportPreview {
    require(ok) { error?.message ?: "chrootctl import preview failed." }
    val root = data as? JsonObject ?: throw ChrootctlBridgeProtocolException("chrootctl import preview data must be an object.")
    val inspection = root["inspection"] as? JsonObject ?: throw ChrootctlBridgeProtocolException("chrootctl import preview is missing inspection data.")
    val metadata = inspection["metadata"] as? JsonObject
    fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
    fun JsonObject.l(k: String) = (this[k] as? JsonPrimitive)?.longOrNull ?: 0L
    fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
    val blocking = (root["blocking"] as? JsonArray).orEmpty().mapNotNull { e ->
        val o=e as? JsonObject ?: return@mapNotNull null
        val c=o.s("code"); val m=o.s("message"); if(c.isBlank()||m.isBlank()) null else ChrootctlPreviewBlocker(c,m)
    }
    val warnings=(root["warnings"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    return ChrootctlImportPreview(
        requestId=requestId, planFingerprint=plan.fingerprint(), contract=ChrootctlBridgeCodec.previewContract(this), stagedArchivePath=staged.archivePath, stagingId=staged.stagingId, stagedChecksumSha256=staged.checksumSha256,
        inspection=ChrootctlImportInspection(
            suggestedName=inspection.s("suggested_name").ifBlank { inspection.s("suggestedName") }, distro=inspection.s("distro"), arch=inspection.s("arch"),
            release=inspection.s("release"), version=inspection.s("version"), sourceBytes=inspection.l("source_bytes"), expandedBytes=inspection.l("expanded_bytes"),
            metadataFidelity=metadata?.s("metadata_fidelity").orEmpty().ifBlank { inspection.s("metadata_fidelity") }, checksumVerified=inspection.b("checksum_verified")
        ), blockers=blocking, warnings=warnings
    )
}

fun ChrootctlOperationEvent.importResultOrNull(): ChrootctlImportResult? {
    if(type != "operation_succeeded") return null
    val result=payload["result"] as? JsonObject ?: return null
    fun s(k:String)=(result[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
    return ChrootctlImportResult(s("name"), s("path"), s("metadata_fidelity"), operationId.takeIf { it.isNotBlank() })
}

private fun String.stableImportToken(): String { var h=1125899906842597L; for(c in this) h=31*h+c.code; return h.toULong().toString(16).padStart(16,'0').repeat(4).take(64) }
