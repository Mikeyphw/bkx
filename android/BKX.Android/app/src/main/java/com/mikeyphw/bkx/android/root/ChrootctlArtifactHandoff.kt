package com.mikeyphw.bkx.android.root

import android.content.Context
import com.mikeyphw.bkx.android.core.bridge.BridgeRequestIdPolicy
import com.mikeyphw.bkx.android.core.chrootctl.ChrootctlExportArtifact
import com.mikeyphw.bkx.android.core.chrootctl.ChrootctlStagedArtifact
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Copies a verified root-owned chrootctl export into BKX-owned external staging.
 * The external-files location intentionally keeps the subsequent BKX transfer on
 * its normal app-UID route for ordinary/rclone destinations.
 */
class ChrootctlArtifactHandoff(
    context: Context,
    private val rootClient: RootBkxServiceClient,
) {
    private val appContext = context.applicationContext ?: context

    fun stagingFreeBytes(): Long {
        val root = stagingRootOrNull() ?: return 0L
        if (!root.exists() && !root.mkdirs()) return 0L
        return root.usableSpace.coerceAtLeast(0L)
    }

    suspend fun stage(
        requestId: String,
        artifact: ChrootctlExportArtifact,
        onBytesCopied: suspend (Long) -> Unit = {},
    ): ChrootctlStagedArtifact = withContext(Dispatchers.IO) {
        val safeRequestId = BridgeRequestIdPolicy.normalize(requestId)
        val root = requireNotNull(stagingRootOrNull()) {
            "BKX external-files staging is unavailable; chroot exports need app-owned staging before publication."
        }
        if (!root.exists() && !root.mkdirs()) error("Could not create BKX chroot export staging directory.")
        val requestDir = File(root, safeRequestId)
        if (!requestDir.exists() && !requestDir.mkdirs()) error("Could not create BKX chroot export request staging directory.")
        val fileName = File(artifact.archivePath).name.takeIf { it.isNotBlank() && '/' !in it && '\\' !in it }
            ?: error("chrootctl returned an invalid export filename.")
        val target = File(requestDir, fileName)
        require(!target.exists()) { "BKX staged chroot export already exists for request $safeRequestId." }
        val partial = File(requestDir, ".$fileName.partial")
        if (partial.exists() && !partial.delete()) error("Could not clear an incomplete BKX chroot export staging file.")

        val digest = MessageDigest.getInstance("SHA-256")
        val copied = AtomicLong(0L)
        try {
            FileOutputStream(partial).use { fileOutput ->
                val digestOutput = DigestOutputStream(BufferedOutputStream(fileOutput, COPY_BUFFER_BYTES), digest)
                val startRaw = rootClient.chrootctlCopyManagedExportArtifact(
                    copyId = "handoff-$safeRequestId",
                    path = artifact.archivePath,
                    output = digestOutput,
                ) { bytes ->
                    copied.set(bytes)
                    onBytesCopied(bytes)
                }
                val start = JSONObject(startRaw)
                check(start.optBoolean("ok", false)) {
                    start.optJSONObject("error")?.optString("message").orEmpty().ifBlank { "chrootctl artifact handoff could not start." }
                }
                val expectedSize = start.optJSONObject("data")?.optLong("sizeBytes", -1L) ?: -1L
                digestOutput.flush()
                fileOutput.fd.sync()
                val actualSize = partial.length()
                check(expectedSize > 0L && actualSize == expectedSize && copied.get() == actualSize) {
                    "chrootctl artifact handoff ended with an incomplete byte count."
                }
            }
            val actualChecksum = digest.digest().joinToString("") { "%02x".format(it) }
            check(actualChecksum.equals(artifact.checksumSha256, ignoreCase = true)) {
                "BKX staging checksum does not match the checksum produced by chrootctl."
            }
            check(partial.renameTo(target)) { "Could not atomically publish the BKX staged chroot export." }
            ChrootctlStagedArtifact(
                sourceManagedPath = artifact.archivePath,
                stagedPath = target.absolutePath,
                fileName = fileName,
                sizeBytes = target.length(),
                checksumSha256 = actualChecksum,
            )
        } catch (error: Throwable) {
            partial.delete()
            if (target.exists()) target.delete()
            throw error
        }
    }

    fun cleanup(staged: ChrootctlStagedArtifact): Boolean {
        val root = stagingRootOrNull()?.canonicalFile ?: return false
        val file = File(staged.stagedPath).canonicalFile
        if (!file.path.startsWith(root.path + File.separator) || file == root) return false
        val removed = !file.exists() || (file.isFile && file.delete())
        if (!removed) return false
        val parent = file.parentFile
        if (parent != null && parent != root && parent.path.startsWith(root.path + File.separator)) {
            parent.listFiles()?.takeIf { it.isEmpty() }?.let { parent.delete() }
        }
        return true
    }

    private fun stagingRootOrNull(): File? = appContext.getExternalFilesDir(null)?.resolve("bkx/chrootctl-staging")

    private companion object {
        const val COPY_BUFFER_BYTES = 256 * 1024
    }
}
