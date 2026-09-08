package com.mikeyphw.bkx.android.contracts

import com.mikeyphw.bkx.android.core.drive.GoogleDriveRcloneConfig
import com.mikeyphw.bkx.android.core.remotes.BKX_GOOGLE_DRIVE_REMOTE_NAME
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleDriveRcloneConfigTest {
    @Test
    fun managedSectionIsInsertedWithoutDestroyingImportedRemotes() {
        val original = """
            [s3]
            type = s3
            access_key_id = secret-id
        """.trimIndent() + "\n"
        val updated = GoogleDriveRcloneConfig.upsertAccessToken(original, "ya29.test-token")

        assertTrue(updated.contains("[s3]"))
        assertTrue(updated.contains("[$BKX_GOOGLE_DRIVE_REMOTE_NAME]"))
        assertTrue(updated.contains("type = drive"))
        assertTrue(updated.contains("scope = drive.file"))
        assertTrue(updated.contains("\"access_token\":\"ya29.test-token\""))
        assertTrue(GoogleDriveRcloneConfig.containsManagedSection(updated))
    }

    @Test
    fun tokenRefreshReplacesOnlyManagedSection() {
        val first = GoogleDriveRcloneConfig.upsertAccessToken("[box]\ntype = box\n", "first")
        val second = GoogleDriveRcloneConfig.upsertAccessToken(first, "second")

        assertFalse(second.contains("\"access_token\":\"first\""))
        assertTrue(second.contains("\"access_token\":\"second\""))
        assertTrue(second.contains("[box]"))
        assertTrue(second.split("[$BKX_GOOGLE_DRIVE_REMOTE_NAME]").size == 2)
    }

    @Test
    fun importedConfigCannotTakeOverManagedRemoteName() {
        val existing = GoogleDriveRcloneConfig.upsertAccessToken("", "token")
        val imported = "[$BKX_GOOGLE_DRIVE_REMOTE_NAME]\ntype = drive\n"
        val result = runCatching { GoogleDriveRcloneConfig.preserveManagedSection(existing, imported) }
        assertTrue(result.isFailure)
    }

    @Test
    fun removingManagedSectionPreservesOtherProviders() {
        val withManaged = GoogleDriveRcloneConfig.upsertAccessToken("[dropbox]\ntype = dropbox\n", "token")
        val removed = GoogleDriveRcloneConfig.removeManagedSection(withManaged)
        assertTrue(removed.contains("[dropbox]"))
        assertFalse(removed.contains("[$BKX_GOOGLE_DRIVE_REMOTE_NAME]"))
        assertFalse(removed.contains("token"))
    }
}
