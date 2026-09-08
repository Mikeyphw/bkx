package com.mikeyphw.bkx.android.contracts

import com.mikeyphw.bkx.android.core.remotes.RcloneConfigParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RcloneConfigParserTest {
    @Test
    fun parserExposesOnlyRemoteNamesAndBackendTypes() {
        val snapshot = RcloneConfigParser.parse(
            """
            [drive]
            type = drive
            token = secret-token-that-must-not-surface

            [archive_s3]
            type = s3
            password = secret-password-that-must-not-surface
            """.trimIndent(),
        )

        assertTrue(snapshot.configured)
        assertEquals(listOf("archive_s3", "drive"), snapshot.profiles.map { it.name })
        assertEquals(listOf("s3", "drive"), snapshot.profiles.map { it.type })
        assertTrue(snapshot.ignoredRemoteNames.isEmpty())
        assertTrue(snapshot.profiles.none { it.toString().contains("secret", ignoreCase = true) })
    }

    @Test
    fun parserSeparatesRemoteNamesThatCannotBeRepresentedInBkxUri() {
        val snapshot = RcloneConfigParser.parse(
            """
            [valid-remote]
            type = webdav
            [unsafe remote]
            type = drive
            """.trimIndent(),
        )
        assertEquals(listOf("valid-remote"), snapshot.profiles.map { it.name })
        assertEquals(listOf("unsafe remote"), snapshot.ignoredRemoteNames)
    }
}
