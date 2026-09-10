package com.mikeyphw.bkx.android.core.jobs

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

const val SAVED_JOB_SCHEMA_VERSION = 2
const val OPERATION_SPEC_V2_SCHEMA_VERSION = 2

@Serializable
data class SecretReference(
    val id: String,
    val purpose: String = "",
) {
    init {
        require(referenceIdRegex.matches(id)) { "credential reference id must be a non-secret stable reference id" }
        require(purpose == purpose.trim() && purpose.length <= 160) { "credential reference purpose must be trimmed and at most 160 characters" }
    }
}

@Serializable
data class OperationSpecV2(
    @SerialName("schema_version") val schemaVersion: Int = OPERATION_SPEC_V2_SCHEMA_VERSION,
    val operation: String,
    val parameters: JsonObject = buildJsonObject { },
    @SerialName("repository_ref") val repositoryRef: String? = null,
    @SerialName("credential_refs") val credentialRefs: List<SecretReference> = emptyList(),
) {
    init {
        require(schemaVersion == OPERATION_SPEC_V2_SCHEMA_VERSION) { "operation spec schema_version must be $OPERATION_SPEC_V2_SCHEMA_VERSION" }
        require(operationIdRegex.matches(operation)) { "operation must be a stable lower-case operation id" }
        require(repositoryRef == null || referenceIdRegex.matches(repositoryRef)) { "repository_ref must be a non-secret stable reference id" }
        require(credentialRefs.map { it.id }.distinct().size == credentialRefs.size) { "credential reference ids must be unique" }
        parameters.rejectInlineSecrets("parameters")
    }
}

@Serializable
data class SavedJob(
    @SerialName("schema_version") val schemaVersion: Int = SAVED_JOB_SCHEMA_VERSION,
    @SerialName("job_id") val jobId: String,
    val name: String,
    val description: String = "",
    val tags: List<String> = emptyList(),
    val revision: Long,
    val enabled: Boolean = true,
    val operation: OperationSpecV2,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
) {
    init {
        validateJobIdentity(jobId, name, description, tags)
        require(schemaVersion == SAVED_JOB_SCHEMA_VERSION) { "saved job schema_version must be $SAVED_JOB_SCHEMA_VERSION" }
        require(revision >= 1) { "saved job revision must be at least 1" }
        require(createdAt.isNotBlank()) { "created_at must not be blank" }
        require(updatedAt.isNotBlank()) { "updated_at must not be blank" }
    }
}

@Serializable
data class SavedJobDraft(
    @SerialName("job_id") val jobId: String,
    val name: String,
    val description: String = "",
    val tags: List<String> = emptyList(),
    val enabled: Boolean = true,
    val operation: OperationSpecV2,
) {
    init {
        validateJobIdentity(jobId, name, description, tags)
    }
}

@Serializable
data class SavedJobStoreDocument(
    @SerialName("schema_version") val schemaVersion: Int = SAVED_JOB_SCHEMA_VERSION,
    val jobs: List<SavedJob> = emptyList(),
    val revisions: Map<String, List<SavedJob>> = emptyMap(),
) {
    init {
        require(schemaVersion == SAVED_JOB_SCHEMA_VERSION) { "saved-job document schema_version must be $SAVED_JOB_SCHEMA_VERSION" }
        require(jobs.map { it.jobId }.distinct().size == jobs.size) { "saved-job document must not contain duplicate job ids" }
        revisions.forEach { (jobId, history) ->
            require(history.all { it.jobId == jobId }) { "saved-job revision history contains the wrong job id" }
            require(history.zipWithNext().all { (left, right) -> left.revision < right.revision }) { "saved-job revision history must be strictly increasing" }
        }
    }
}

private val operationIdRegex = Regex("^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$")
private val jobIdRegex = Regex("^[a-z0-9][a-z0-9._-]{0,127}$")
private val referenceIdRegex = Regex("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$")
private val forbiddenSecretKeys = setOf(
    "password", "passwd", "passphrase", "secret", "clientsecret", "privatekey",
    "token", "accesstoken", "refreshtoken", "authorization", "apikey", "credential", "credentials",
)

private fun validateJobIdentity(jobId: String, name: String, description: String, tags: List<String>) {
    require(jobIdRegex.matches(jobId)) { "job_id must contain only lower-case letters, numbers, dot, underscore, or dash" }
    require(name.isNotBlank() && name == name.trim() && name.length <= 120) { "job name must be trimmed, non-empty, and at most 120 characters" }
    require(description == description.trim() && description.length <= 500) { "job description must be trimmed and at most 500 characters" }
    require(tags.all { it.isNotBlank() && it == it.trim() && it.length <= 64 }) { "job tags must be trimmed, non-empty, and at most 64 characters" }
    require(tags.distinct().size == tags.size) { "job tags must be unique" }
}

private fun normalizedSecretKey(key: String): String = key.lowercase().filter { it.isLetterOrDigit() }

private fun JsonElement.rejectInlineSecrets(path: String) {
    when (this) {
        is JsonObject -> entries.forEach { (key, value) ->
            require(normalizedSecretKey(key) !in forbiddenSecretKeys) {
                "saved operation parameters must not contain inline secret field $path.$key; use credential_refs"
            }
            value.rejectInlineSecrets("$path.$key")
        }
        is JsonArray -> forEachIndexed { index, value -> value.rejectInlineSecrets("$path[$index]") }
        else -> Unit
    }
}
