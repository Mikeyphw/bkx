package com.mikeyphw.bkx.android.core.remotes

import android.content.Context
import android.net.Uri
import com.mikeyphw.bkx.android.core.drive.GoogleDriveRcloneConfig
import java.io.FileOutputStream

internal const val MAX_RCLONE_CONFIG_BYTES = 2L * 1024L * 1024L

/**
 * Owns BKX's app-private rclone.conf. Credentials and tokens stay in this file;
 * consumers only receive parsed remote names and backend type identifiers.
 */
class RcloneConfigManager(context: Context) {
    private val appContext = context.applicationContext ?: context
    private val directory = appContext.filesDir.resolve("bkx/rclone")
    private val configFile = directory.resolve("rclone.conf")

    fun snapshot(): RcloneConfigSnapshot {
        if (!configFile.isFile || configFile.length() <= 0L) return RcloneConfigSnapshot(false, emptyList())
        return RcloneConfigParser.parse(configFile.readText(Charsets.UTF_8))
    }

    fun importConfig(uri: Uri): RcloneConfigSnapshot {
        directory.mkdirs()
        val temp = directory.resolve("rclone.conf.import")
        temp.delete()
        try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        copied += read
                        require(copied <= MAX_RCLONE_CONFIG_BYTES) { "rclone.conf is larger than the 2 MiB import limit." }
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            } ?: error("Could not open the selected rclone.conf.")

            require(temp.length() > 0L) { "The selected rclone.conf is empty." }
            val text = temp.readText(Charsets.UTF_8)
            require(!text.startsWith("RCLONE_ENCRYPT_V0:")) {
                "Encrypted rclone.conf files need a config password and are not supported by BKX yet."
            }
            val parsed = RcloneConfigParser.parse(text)
            require(parsed.profiles.isNotEmpty()) {
                if (parsed.ignoredRemoteNames.isNotEmpty()) {
                    "The config only contains remote names that BKX cannot address. Rename them to letters, numbers, dot, underscore, or dash."
                } else {
                    "No rclone remote sections were found in the selected config."
                }
            }
            require(parsed.profiles.none { it.name == BKX_GOOGLE_DRIVE_REMOTE_NAME }) {
                "The remote name $BKX_GOOGLE_DRIVE_REMOTE_NAME is reserved for BKX Google Drive."
            }
            val existing = configFile.takeIf { it.isFile }?.readText(Charsets.UTF_8).orEmpty()
            val merged = GoogleDriveRcloneConfig.preserveManagedSection(existing, text)
            writeStaging(temp, merged)
            publishAtomically(temp)
            lockOwnerOnly()
            return snapshot()
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
    }

    fun deleteConfig() {
        val existing = configFile.takeIf { it.isFile }?.readText(Charsets.UTF_8).orEmpty()
        val retained = if (GoogleDriveRcloneConfig.containsManagedSection(existing)) {
            GoogleDriveRcloneConfig.preserveManagedSection(existing, "")
        } else {
            ""
        }
        if (retained.isBlank()) {
            if (configFile.exists() && !configFile.delete()) error("Could not remove the BKX rclone configuration.")
        } else {
            publishText(retained)
        }
    }

    fun updateManagedGoogleDriveToken(accessToken: String): RcloneConfigSnapshot {
        val existing = configFile.takeIf { it.isFile }?.readText(Charsets.UTF_8).orEmpty()
        publishText(GoogleDriveRcloneConfig.upsertAccessToken(existing, accessToken))
        return snapshot()
    }

    fun removeManagedGoogleDrive(): RcloneConfigSnapshot {
        val existing = configFile.takeIf { it.isFile }?.readText(Charsets.UTF_8).orEmpty()
        val next = GoogleDriveRcloneConfig.removeManagedSection(existing)
        if (next.isBlank()) {
            if (configFile.exists() && !configFile.delete()) error("Could not remove managed Google Drive configuration.")
        } else {
            publishText(next)
        }
        return snapshot()
    }

    fun hasManagedGoogleDrive(): Boolean = snapshot().profiles.any { it.managedByBkx && it.type.equals("drive", ignoreCase = true) }

    fun configPathForDiagnostics(): String = configFile.absolutePath

    private fun publishText(text: String) {
        directory.mkdirs()
        val temp = directory.resolve("rclone.conf.managed")
        temp.delete()
        writeStaging(temp, text)
        publishAtomically(temp)
        lockOwnerOnly()
    }

    private fun writeStaging(temp: java.io.File, text: String) {
        FileOutputStream(temp).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    private fun publishAtomically(temp: java.io.File) {
        val previous = directory.resolve("rclone.conf.previous")
        previous.delete()
        if (configFile.exists() && !configFile.renameTo(previous)) {
            error("Could not stage the existing BKX rclone configuration for replacement.")
        }
        if (!temp.renameTo(configFile)) {
            if (previous.exists()) previous.renameTo(configFile)
            error("Could not publish the imported BKX rclone configuration.")
        }
        previous.delete()
    }

    private fun lockOwnerOnly() {
        configFile.setReadable(false, false)
        configFile.setWritable(false, false)
        configFile.setReadable(true, true)
        configFile.setWritable(true, true)
    }
}
