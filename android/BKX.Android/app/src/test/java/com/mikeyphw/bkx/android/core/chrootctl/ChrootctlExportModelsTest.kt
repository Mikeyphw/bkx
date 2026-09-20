package com.mikeyphw.bkx.android.core.chrootctl

import com.mikeyphw.bkx.android.core.backup.BackupDestinationSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChrootctlExportModelsTest {
    private val destination = BackupDestinationSelection(
        destinationId = "drive-folder",
        label = "Drive / Chroots",
        stagedDirectoryPath = "rclone://bkx-google-drive/BKX/Chroots",
    )

    @Test
    fun `preview keeps chrootctl local and blocks insufficient BKX staging`() {
        val plan = ChrootctlExportPlan("androidos", destination)
        val response = ChrootctlBridgeCodec.decodeResponse(
            """{
              "protocolVersion":1,"requestId":"preview-1","ok":true,
              "data":{
                "ok":true,"kind":"export","name":"androidos",
                "archive":"/data/local/tmp/chrootctl-transfer/androidos.tar.zst",
                "size_bytes":1073741824,"required_bytes":1073741824,
                "free_bytes":8589934592,"enough_space":true,
                "blocking":[],"warnings":[],
                "previewId":"0123456789abcdef0123456789abcdef",
                "payloadHash":"hash","resourceRevision":"revision",
                "actionOperation":"transfer.export"
              },"warnings":[],"nextSteps":[]
            }""".trimIndent(),
            "preview-1",
        )
        val preview = response.toChrootctlExportPreview(
            plan = plan,
            bkxStagingFreeBytes = 512L * 1024L * 1024L,
            bkxStagingReserveBytes = 64L * 1024L * 1024L,
        )
        assertFalse(preview.isExecutable)
        assertTrue(preview.blockers.any { it.code == "bkx_staging_space" })
        assertTrue(preview.managedArchivePath.startsWith("/data/local/tmp/chrootctl-transfer/"))
    }

    @Test
    fun `preview fails closed when chrootctl says not executable without blockers`() {
        val plan = ChrootctlExportPlan("androidos", destination)
        val response = ChrootctlBridgeCodec.decodeResponse(
            """{
              "protocolVersion":1,"requestId":"preview-2","ok":true,
              "data":{
                "ok":false,"archive":"/data/local/tmp/chrootctl-transfer/androidos.tar.zst",
                "size_bytes":1,"required_bytes":1,"free_bytes":2,
                "blocking":[],"warnings":[],
                "previewId":"0123456789abcdef0123456789abcdef",
                "payloadHash":"hash","resourceRevision":"revision"
              },"warnings":[],"nextSteps":[]
            }""".trimIndent(),
            "preview-2",
        )
        val preview = response.toChrootctlExportPreview(plan, 1024L * 1024L * 1024L, 0L)
        assertFalse(preview.isExecutable)
        assertTrue(preview.blockers.any { it.code == "chrootctl_preview_not_executable" })
    }

    @Test
    fun `successful event translates exact managed export identity and checksum`() {
        val event = ChrootctlBridgeCodec.decodeEvent(
            """{
              "protocolVersion":1,"type":"operation_succeeded","operationId":"abc123",
              "requestId":"export-1","timestamp":1.0,
              "payload":{"result":{
                "name":"androidos","archive":"/data/local/tmp/chrootctl-transfer/androidos.tar.zst",
                "arch":"arm64","checksum":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "checksum_path":"/data/local/tmp/chrootctl-transfer/androidos.tar.zst.sha256","encrypted":false
              }}
            }""".trimIndent(),
            "export-1",
        )
        val artifact = requireNotNull(event.exportArtifactOrNull())
        assertEquals("androidos", artifact.chrootName)
        assertEquals("/data/local/tmp/chrootctl-transfer/androidos.tar.zst", artifact.archivePath)
        assertEquals("a".repeat(64), artifact.checksumSha256)
        assertFalse(artifact.encrypted)
    }

    @Test
    fun `plan fingerprint binds destination and overwrite choice`() {
        val original = ChrootctlExportPlan("androidos", destination)
        val differentDestination = original.copy(
            destination = destination.copy(stagedDirectoryPath = "rclone://bkx-google-drive/BKX/Other"),
        )
        val overwrite = original.copy(overwriteExisting = true)
        assertNotEquals(original.fingerprint(), differentDestination.fingerprint())
        assertNotEquals(original.fingerprint(), overwrite.fingerprint())
        assertEquals(original.fingerprint(), original.copy().fingerprint())
    }
}
