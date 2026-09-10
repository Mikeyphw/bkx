package com.mikeyphw.bkx.android.core.repositories

import com.mikeyphw.bkx.android.core.backup.BackupEngineKind
import com.mikeyphw.bkx.android.core.drive.GoogleDriveRcloneConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val REPOSITORY_DEFINITION_SCHEMA_VERSION: Int = 1

@Serializable
enum class RepositoryBackendKind(val wireValue: String) {
    @SerialName("local") Local("local"),
    @SerialName("rclone") Rclone("rclone"),
    @SerialName("managed_google_drive") ManagedGoogleDrive("managed_google_drive"),
}

@Serializable
data class RepositoryCreateSpec(
    @SerialName("repository_id") val repositoryId: String,
    val name: String,
    val engine: String = BackupEngineKind.Restic.wireValue,
    val backend: String,
    val location: String,
    @SerialName("credential_ref") val credentialRef: String? = null,
) {
    init {
        requireRepositoryId(repositoryId)
        require(name.isNotBlank()) { "repository name must not be blank" }
        require(engine == BackupEngineKind.Restic.wireValue) { "named repository engine must be restic" }
        require(RepositoryBackendKind.entries.any { it.wireValue == backend }) { "unsupported repository backend" }
        require(location.isNotBlank()) { "repository location must not be blank" }
        require(credentialRef == null || credentialRef.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$"))) {
            "credential_ref must be a stable reference id"
        }
    }
}

@Serializable
data class RepositoryDefinition(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("repository_id") val repositoryId: String,
    val name: String,
    val engine: String,
    val backend: String,
    val location: String,
    @SerialName("credential_ref") val credentialRef: String? = null,
    @SerialName("managed_by_bkx") val managedByBkx: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
) {
    init {
        require(schemaVersion == REPOSITORY_DEFINITION_SCHEMA_VERSION) { "unsupported repository schema version" }
        requireRepositoryId(repositoryId)
        require(name.isNotBlank()) { "repository name must not be blank" }
        require(engine == BackupEngineKind.Restic.wireValue) { "named repository engine must be restic" }
        require(RepositoryBackendKind.entries.any { it.wireValue == backend }) { "unsupported repository backend" }
        require(location.isNotBlank()) { "repository location must not be blank" }
        require(createdAt.isNotBlank() && updatedAt.isNotBlank()) { "repository timestamps must not be blank" }
        require(credentialRef == null || credentialRef.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$"))) {
            "credential_ref must be a stable reference id"
        }
        if (backend == RepositoryBackendKind.ManagedGoogleDrive.wireValue) {
            require(managedByBkx) { "managed Google Drive repository must be marked managed_by_bkx" }
            require(location == GoogleDriveRcloneConfig.rootUri || location.startsWith("${GoogleDriveRcloneConfig.rootUri}/")) {
                "managed Google Drive repository must stay under the BKX Drive root"
            }
        } else {
            require(!managedByBkx) { "only managed Google Drive repositories may be marked managed_by_bkx" }
        }
    }
}

object RepositorySpecs {
    fun local(
        repositoryId: String,
        name: String,
        location: String,
        credentialRef: String? = null,
    ): RepositoryCreateSpec = RepositoryCreateSpec(
        repositoryId = repositoryId,
        name = name,
        backend = RepositoryBackendKind.Local.wireValue,
        location = location,
        credentialRef = credentialRef,
    )

    fun rclone(
        repositoryId: String,
        name: String,
        location: String,
        credentialRef: String? = null,
    ): RepositoryCreateSpec = RepositoryCreateSpec(
        repositoryId = repositoryId,
        name = name,
        backend = RepositoryBackendKind.Rclone.wireValue,
        location = location,
        credentialRef = credentialRef,
    )

    fun managedGoogleDrive(
        repositoryId: String,
        name: String,
        childPath: String = "restic/$repositoryId",
        credentialRef: String? = null,
    ): RepositoryCreateSpec {
        val child = childPath.trim().trim('/')
        require(child.isNotBlank()) { "managed Google Drive child path must not be blank" }
        require(!child.contains("..")) { "managed Google Drive child path must not escape the BKX root" }
        return RepositoryCreateSpec(
            repositoryId = repositoryId,
            name = name,
            backend = RepositoryBackendKind.ManagedGoogleDrive.wireValue,
            location = "${GoogleDriveRcloneConfig.rootUri}/$child",
            credentialRef = credentialRef,
        )
    }
}

private fun requireRepositoryId(value: String) {
    require(value.matches(Regex("^[a-z0-9][a-z0-9._-]{0,127}$"))) {
        "repository_id must use lowercase letters, numbers, dot, underscore, or dash"
    }
}
