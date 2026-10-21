package com.mikeyphw.bkx.android.root

import java.io.File
import org.json.JSONObject

internal data class TrustedChrootctlExportArtifact(
    val artifactId: String,
    val path: String,
    val sizeBytes: Long,
    val managedRoot: String,
)

/**
 * Validates one path against chrootctl's authoritative managed-artifact registry.
 * A caller-provided root path is never sufficient by itself.
 */
internal object ChrootctlManagedArtifactPolicy {
    private const val EXPORT_KIND = "chroot-export"
    private const val MAX_MANAGED_EXPORT_BYTES = 16L * 1024L * 1024L * 1024L

    fun resolveExport(responseJson: String, requestedPath: String): TrustedChrootctlExportArtifact {
        require(requestedPath.startsWith('/')) { "Managed chrootctl export path must be absolute." }
        val envelope = JSONObject(responseJson)
        require(envelope.optBoolean("ok", false)) { "chrootctl artifact registry query failed." }
        val data = envelope.optJSONObject("data") ?: error("chrootctl artifact registry response has no data.")
        val rootText = data.optString("managedRoot", data.optString("directory", "")).trim()
        require(rootText.startsWith('/')) { "chrootctl managed artifact root is invalid." }
        val configuredRoot = File(rootText)
        require(configuredRoot.canonicalFile == configuredRoot.absoluteFile) { "chrootctl managed artifact root must not be a symlink." }
        val root = configuredRoot.canonicalFile
        require(root.isDirectory) { "chrootctl managed artifact root is not a directory." }
        val requested = File(requestedPath)
        require(requested.canonicalFile == requested.absoluteFile) { "Managed chrootctl export must not be a symlink." }
        val canonical = requested.canonicalFile
        require(canonical.path.startsWith(root.path + File.separator) && canonical != root) {
            "Requested export is outside the chrootctl managed artifact root."
        }
        require(canonical.isFile) { "Requested chrootctl export is not a regular file." }

        val items = data.optJSONArray("items") ?: error("chrootctl artifact registry response has no items.")
        var match: JSONObject? = null
        for (index in 0 until items.length()) {
            val row = items.optJSONObject(index) ?: continue
            if (row.optString("path") == canonical.path) {
                match = row
                break
            }
        }
        val row = requireNotNull(match) { "Requested export is not registered as a managed chrootctl artifact." }
        require(row.optBoolean("managed", false)) { "Requested chrootctl artifact is not managed." }
        require(row.optString("kind") == EXPORT_KIND) { "Requested chrootctl artifact is not a chroot export." }
        val registeredSize = row.optLong("sizeBytes", row.optLong("size_bytes", -1L))
        require(registeredSize > 0L && registeredSize <= MAX_MANAGED_EXPORT_BYTES) { "Managed chrootctl export size is invalid." }
        require(canonical.length() == registeredSize) { "Managed chrootctl export size changed after registry inspection." }
        val artifactId = row.optString("artifactId", row.optString("artifact_id", "")).trim()
        require(artifactId.isNotEmpty()) { "Managed chrootctl export has no artifact id." }
        return TrustedChrootctlExportArtifact(
            artifactId = artifactId,
            path = canonical.path,
            sizeBytes = registeredSize,
            managedRoot = root.path,
        )
    }
}
