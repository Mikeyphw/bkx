package com.mikeyphw.bkx.android.core.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class BackupEngineKind(val wireValue: String) {
    @SerialName("bakery") Bakery("bakery"),
    @SerialName("restic") Restic("restic"),
    @SerialName("chrootctl") Chrootctl("chrootctl"),
}

@Serializable
data class SnapshotBackupSpec(
    val engine: String = BackupEngineKind.Restic.wireValue,
    val repository: String,
    @SerialName("credential_ref") val credentialRef: String? = null,
    val sources: List<String>,
    val excludes: List<String> = emptyList(),
    val includes: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val verify: Boolean = true,
    @SerialName("dry_run") val dryRun: Boolean = false,
) {
    init {
        require(engine == BackupEngineKind.Restic.wireValue) { "snapshot backup engine must be restic" }
        require(repository.isNotBlank()) { "snapshot backup repository must not be blank" }
        require(sources.isNotEmpty() && sources.none { it.isBlank() }) { "snapshot backup requires non-blank sources" }
        require(credentialRef == null || credentialRef.isNotBlank()) { "credential_ref must be non-blank when provided" }
        require(includes.isEmpty()) { "Restic include filters are deferred until the snapshot filter contract is implemented" }
    }
}

@Serializable
data class RepositoryInitSpec(
    val engine: String = BackupEngineKind.Restic.wireValue,
    val repository: String,
    @SerialName("credential_ref") val credentialRef: String? = null,
) {
    init {
        require(engine == BackupEngineKind.Restic.wireValue) { "repository init engine must be restic" }
        require(repository.isNotBlank()) { "repository must not be blank" }
        require(credentialRef == null || credentialRef.isNotBlank()) { "credential_ref must be non-blank when provided" }
    }
}
