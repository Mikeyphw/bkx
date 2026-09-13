package com.mikeyphw.bkx.android.core.repositories

import com.mikeyphw.bkx.android.core.bridge.BridgeArtifact
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object SnapshotBridgeCodec {
    fun decodeSnapshots(artifacts: List<BridgeArtifact>): List<SnapshotDescriptor> =
        artifacts.filter { it.label == "restic_snapshot" }.mapNotNull(::decodeSnapshot)

    fun decodeSnapshot(artifact: BridgeArtifact): SnapshotDescriptor? {
        if (artifact.label != "restic_snapshot") return null
        val metadata = artifact.metadata
        val id = metadata["snapshot_id"] ?: artifact.path.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return null
        val time = metadata["time"] ?: return null
        return runCatching {
            SnapshotDescriptor(
                id = id,
                shortId = metadata["short_id"].orEmpty().ifBlank { id.take(8) },
                time = time,
                hostname = metadata["hostname"].orEmpty(),
                username = metadata["username"].orEmpty(),
                paths = decodeStringArray(metadata["paths"]),
                tags = decodeStringArray(metadata["tags"]),
                size = metadata["size"]?.toULongOrNull(),
            )
        }.getOrNull()
    }

    fun decodeNodes(artifacts: List<BridgeArtifact>): List<SnapshotNodeDescriptor> =
        artifacts.filter { it.label == "snapshot_node" }.mapNotNull { artifact ->
            val type = artifact.metadata["type"] ?: return@mapNotNull null
            val name = artifact.metadata["name"] ?: artifact.path.substringAfterLast('/').ifBlank { "/" }
            runCatching {
                SnapshotNodeDescriptor(
                    name = name,
                    type = type,
                    path = artifact.path,
                    size = artifact.metadata["size"]?.toULongOrNull(),
                    permissions = artifact.metadata["permissions"],
                    modifiedAt = artifact.metadata["mtime"],
                )
            }.getOrNull()
        }

    fun decodeDiff(artifacts: List<BridgeArtifact>): SnapshotDiffSummary {
        val entries = artifacts.filter { it.label == "snapshot_diff_entry" }.map { artifact ->
            SnapshotDiffEntry(path = artifact.path, modifier = artifact.metadata["modifier"].orEmpty())
        }
        val statsArtifact = artifacts.firstOrNull { it.label == "snapshot_diff_statistics" }
        val stats = statsArtifact?.metadata?.get("json")
        val truncated = statsArtifact?.metadata?.get("truncated")?.toBooleanStrictOrNull() ?: false
        val limit = statsArtifact?.metadata?.get("limit")?.toIntOrNull()
        if (stats.isNullOrBlank()) return SnapshotDiffSummary(entries = entries, changedFiles = entries.size.toLong(), truncated = truncated, limit = limit)
        return runCatching {
            val root = Json.parseToJsonElement(stats).jsonObject
            val added = root["added"]?.jsonObject
            val removed = root["removed"]?.jsonObject
            SnapshotDiffSummary(
                entries = entries,
                changedFiles = root["changed_files"]?.jsonPrimitive?.content?.toLongOrNull() ?: entries.size.toLong(),
                bytesAdded = added?.get("bytes")?.jsonPrimitive?.content?.toULongOrNull(),
                bytesRemoved = removed?.get("bytes")?.jsonPrimitive?.content?.toULongOrNull(),
                truncated = truncated,
                limit = limit,
            )
        }.getOrElse { SnapshotDiffSummary(entries = entries, changedFiles = entries.size.toLong(), truncated = truncated, limit = limit) }
    }

    fun decodeRestoreResult(artifacts: List<BridgeArtifact>): SnapshotRestoreResult? {
        val artifact = artifacts.firstOrNull { it.label == "snapshot_restore_target" } ?: return null
        val metadata = artifact.metadata
        return SnapshotRestoreResult(
            target = artifact.path,
            snapshotId = metadata["snapshot_id"].orEmpty(),
            overwrite = metadata["overwrite"].orEmpty(),
            dryRun = metadata["dry_run"]?.toBooleanStrictOrNull() ?: false,
            filesRestored = metadata["files_restored"]?.toLongOrNull() ?: 0L,
            filesSkipped = metadata["files_skipped"]?.toLongOrNull() ?: 0L,
            bytesRestored = metadata["bytes_restored"]?.toLongOrNull() ?: 0L,
            bytesSkipped = metadata["bytes_skipped"]?.toLongOrNull() ?: 0L,
            totalFiles = metadata["total_files"]?.toLongOrNull() ?: 0L,
            totalBytes = metadata["total_bytes"]?.toLongOrNull() ?: 0L,
        )
    }

    private fun decodeStringArray(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            Json.parseToJsonElement(raw).jsonArray
                .map { it.jsonPrimitive.content }
                .filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }
}
