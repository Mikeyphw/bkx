package com.mikeyphw.bkx.android.core.repositories

import com.mikeyphw.bkx.android.core.bridge.BridgeArtifact
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SnapshotBridgeUiContractTest {
    private val repository = RepositoryDefinition(
        schemaVersion = 1,
        repositoryId = "phone",
        name = "Phone",
        engine = "restic",
        backend = "local",
        location = "/tmp/repo",
        managedByBkx = false,
        createdAt = "2026-09-10T10:00:00Z",
        updatedAt = "2026-09-10T10:00:00Z",
    )

    @Test
    fun browseRequestUsesRepositoryReferenceAndAbsoluteSnapshotPath() {
        val request = RepositoryRequestBuilder.snapshotBrowse("browse-1", repository, "abc12345", "/Documents")
        assertEquals("snapshots.browse", request.operation)
        assertEquals("\"phone\"", request.payload["repository_ref"].toString())
        assertEquals("\"abc12345\"", request.payload["snapshot"].toString())
        assertEquals("\"/Documents\"", request.payload["path"].toString())
        assertFalse(request.payload.toString().contains("password", ignoreCase = true))
    }

    @Test
    fun snapshotCodecPreservesJsonArrayPathsWithCommas() {
        val snapshot = SnapshotBridgeCodec.decodeSnapshot(
            BridgeArtifact(
                label = "restic_snapshot",
                path = "restic://snapshot/abcdef123456",
                metadata = mapOf(
                    "snapshot_id" to "abcdef123456",
                    "short_id" to "abcdef12",
                    "time" to "2026-09-10T10:00:00Z",
                    "paths" to "[\"/Documents, Work\",\"/DCIM\"]",
                    "tags" to "[\"phone\",\"daily\"]",
                ),
            ),
        )!!
        assertEquals(listOf("/Documents, Work", "/DCIM"), snapshot.paths)
        assertEquals(listOf("phone", "daily"), snapshot.tags)
    }

    @Test
    fun nodeAndRestoreCodecsExposeBrowserAndProgressSummary() {
        val nodes = SnapshotBridgeCodec.decodeNodes(
            listOf(
                BridgeArtifact(label = "snapshot_node", path = "/Documents", metadata = mapOf("name" to "Documents", "type" to "dir")),
                BridgeArtifact(label = "snapshot_node", path = "/photo.jpg", metadata = mapOf("name" to "photo.jpg", "type" to "file", "size" to "512")),
            ),
        )
        assertTrue(nodes.first().isDirectory)
        assertEquals(512uL, nodes.last().size)

        val result = SnapshotBridgeCodec.decodeRestoreResult(
            listOf(
                BridgeArtifact(
                    label = "snapshot_restore_target",
                    path = "/restore",
                    metadata = mapOf(
                        "snapshot_id" to "abcdef12",
                        "overwrite" to "never",
                        "dry_run" to "false",
                        "files_restored" to "4",
                        "files_skipped" to "1",
                        "bytes_restored" to "1000",
                        "bytes_skipped" to "20",
                        "total_files" to "5",
                        "total_bytes" to "1020",
                    ),
                ),
            ),
        )!!
        assertEquals(4L, result.filesRestored)
        assertEquals(1000L, result.bytesRestored)
        assertEquals("never", result.overwrite)
    }
}
