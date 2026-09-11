package com.mikeyphw.bkx.android.core.backup

import com.mikeyphw.bkx.android.core.bridge.BridgeJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotBackupModelsTest {
    @Test
    fun snapshotSpecUsesSameResticWireNamesAsGoCore() {
        val encoded = BridgeJson.json.encodeToString(
            SnapshotBackupSpec(
                repository = "/data/local/bkx-restic",
                credentialRef = "phone-repo",
                sources = listOf("/storage/emulated/0/Documents"),
                tags = listOf("phone"),
            ),
        )
        assertTrue(encoded.contains("\"engine\":\"restic\""))
        assertTrue(encoded.contains("\"credential_ref\":\"phone-repo\""))
        assertTrue(encoded.contains("\"repository\":\"/data/local/bkx-restic\""))
        assertFalse(encoded.contains("password"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun snapshotIncludesStayDisabledUntilExplicitlySupported() {
        SnapshotBackupSpec(repository = "/repo", sources = listOf("/source"), includes = listOf("*.pdf"))
    }
}
