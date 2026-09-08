package com.mikeyphw.bkx.android.core.drive

import com.mikeyphw.bkx.android.core.remotes.BKX_GOOGLE_DRIVE_REMOTE_NAME
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object GoogleDriveRcloneConfig {
    const val oauthScope: String = "https://www.googleapis.com/auth/drive.file"
    const val rootUri: String = "rclone://$BKX_GOOGLE_DRIVE_REMOTE_NAME/BKX"

    fun containsManagedSection(text: String): Boolean = findSection(text, BKX_GOOGLE_DRIVE_REMOTE_NAME) != null

    fun upsertAccessToken(existing: String, accessToken: String): String {
        require(accessToken.isNotBlank()) { "Google Drive access token must not be blank." }
        val tokenJson = buildJsonObject {
            put("access_token", accessToken)
            put("token_type", "Bearer")
        }.toString()
        val base = removeManagedSection(existing).trimEnd()
        val section = buildString {
            append('[').append(BKX_GOOGLE_DRIVE_REMOTE_NAME).append("]\n")
            append("type = drive\n")
            append("scope = drive.file\n")
            append("token = ").append(tokenJson).append('\n')
        }
        return if (base.isBlank()) section else "$base\n\n$section"
    }

    fun removeManagedSection(text: String): String {
        val range = findSection(text, BKX_GOOGLE_DRIVE_REMOTE_NAME) ?: return text
        return (text.substring(0, range.first) + text.substring(range.last + 1)).trimEnd().let {
            if (it.isBlank()) "" else "$it\n"
        }
    }

    fun preserveManagedSection(existing: String, imported: String): String {
        require(findSection(imported, BKX_GOOGLE_DRIVE_REMOTE_NAME) == null) {
            "The remote name $BKX_GOOGLE_DRIVE_REMOTE_NAME is reserved for BKX Google Drive. Rename that imported remote first."
        }
        val managed = findSection(existing, BKX_GOOGLE_DRIVE_REMOTE_NAME)?.let { range ->
            existing.substring(range.first, range.last + 1).trim()
        }.orEmpty()
        val cleanImported = imported.trim()
        return when {
            managed.isBlank() -> if (cleanImported.isBlank()) "" else "$cleanImported\n"
            cleanImported.isBlank() -> "$managed\n"
            else -> "$cleanImported\n\n$managed\n"
        }
    }

    private fun findSection(text: String, sectionName: String): IntRange? {
        val header = Regex("(?m)^\\s*\\[([^]\\r\\n]+)]\\s*$")
        val matches = header.findAll(text).toList()
        val index = matches.indexOfFirst { it.groupValues[1].trim() == sectionName }
        if (index < 0) return null
        val start = matches[index].range.first
        val endExclusive = matches.getOrNull(index + 1)?.range?.first ?: text.length
        return start until endExclusive
    }
}
