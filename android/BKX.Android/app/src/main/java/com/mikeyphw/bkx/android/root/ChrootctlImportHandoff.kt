package com.mikeyphw.bkx.android.root

import android.content.Context
import android.os.ParcelFileDescriptor
import com.mikeyphw.bkx.android.core.bridge.BridgeRequestIdPolicy
import com.mikeyphw.bkx.android.core.chrootctl.ChrootctlStagedImport
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** App-owned acquisition staging -> root-owned chrootctl import staging. */
class ChrootctlImportHandoff(context: Context, private val rootClient: RootBkxServiceClient) {
    private val appContext = context.applicationContext ?: context
    fun acquisitionRoot(): File = requireNotNull(appContext.getExternalFilesDir(null)).resolve("bkx/chrootctl-import")
    fun acquisitionFreeBytes(): Long { val r=acquisitionRoot(); if(!r.exists()) r.mkdirs(); return r.usableSpace.coerceAtLeast(0) }

    suspend fun stageIntoChrootctl(requestId: String, archive: File): ChrootctlStagedImport = withContext(Dispatchers.IO) {
        require(archive.isFile && archive.length() > 0) { "BKX import acquisition is missing or empty." }
        val root=acquisitionRoot().canonicalFile
        val file=archive.canonicalFile
        require(file.toPath().startsWith(root.toPath()) && file != root) { "Only BKX-owned import acquisition files may cross the root handoff." }
        val pfd=ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val raw=try { rootClient.chrootctlStageImportArchive(BridgeRequestIdPolicy.normalize(requestId), file.name, pfd) } finally { runCatching { pfd.close() } }
        val envelope=JSONObject(raw)
        check(envelope.optBoolean("ok", false)) { envelope.optJSONObject("error")?.optString("message").orEmpty().ifBlank { "chrootctl import staging failed." } }
        val data=envelope.getJSONObject("data")
        ChrootctlStagedImport(data.getString("archive"), data.getString("checksum").lowercase(), data.getLong("bytes"), data.getString("stagingId"))
    }

    suspend fun cleanupRootStaging(stagingId: String, checksumSha256: String): Boolean = withContext(Dispatchers.IO) {
        val raw=rootClient.chrootctlCleanupImportStaging(BridgeRequestIdPolicy.normalize("chroot-import-cleanup-${java.util.UUID.randomUUID().toString().replace("-", "").take(20)}"), stagingId, checksumSha256)
        val envelope=JSONObject(raw)
        envelope.optBoolean("ok", false) && envelope.optJSONObject("data")?.optBoolean("cleaned", false) == true
    }

    fun cleanupAcquisition(file: File): Boolean {
        val root=acquisitionRoot().canonicalFile; val candidate=file.canonicalFile
        if(candidate==root || !candidate.toPath().startsWith(root.toPath())) return false
        return !candidate.exists() || (candidate.isFile && candidate.delete())
    }
}
