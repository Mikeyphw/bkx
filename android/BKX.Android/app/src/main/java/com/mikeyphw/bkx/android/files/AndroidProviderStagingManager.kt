package com.mikeyphw.bkx.android.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.net.toUri
import com.mikeyphw.bkx.android.core.files.CONTENT_URI_PREFIX
import com.mikeyphw.bkx.android.core.files.ContentUriPathPolicy
import com.mikeyphw.bkx.android.core.files.ProviderStagedItem
import com.mikeyphw.bkx.android.core.files.ProviderStagingPort
import com.mikeyphw.bkx.android.core.files.UserSelectionKind
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Streams content:// resources into app-private cache paths for the exceptional
 * case where a provider (Drive, DocumentsProvider, etc.) has no real path.
 * Direct filesystem/root paths remain the primary BKX storage architecture.
 */
class AndroidProviderStagingManager(
    context: Context,
) : ProviderStagingPort {
    private val appContext = context.applicationContext ?: context
    private val resolver = appContext.contentResolver
    private val stagingRoot = File(appContext.cacheDir, "bkx/provider-staging")
    private val activeLeaseIds = ConcurrentHashMap.newKeySet<String>()

    override suspend fun stageDocument(uri: String): ProviderStagedItem = withContext(Dispatchers.IO) {
        val parsed = requireContentUri(uri)
        persistReadGrant(parsed)
        val name = queryDisplayName(parsed).safeName("provider-file")
        val expectedSize = querySize(parsed)
        reserveCapacity(expectedSize ?: MIN_UNKNOWN_STREAM_RESERVE_BYTES)
        val lease = newRequestDirectory()
        val target = File(lease.directory, name)
        val copy = runCatching { copyDocument(parsed, target) }.getOrElse { error -> releaseStagingLease(lease.id); throw error }
        ProviderStagedItem(
            sourceUri = uri,
            displayName = name,
            stagedPath = target.absolutePath,
            kind = UserSelectionKind.Document,
            filesCopied = 1,
            bytesCopied = copy.bytes,
            sha256 = copy.sha256,
            leaseId = lease.id,
            leaseExpiresAtEpochMillis = lease.expiresAtEpochMillis,
        )
    }

    override suspend fun stageTree(uri: String): ProviderStagedItem = withContext(Dispatchers.IO) {
        val treeUri = requireContentUri(uri)
        persistReadGrant(treeUri)
        reserveCapacity(MIN_UNKNOWN_TREE_RESERVE_BYTES)
        val treeId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootDocument = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId)
        val rootName = queryDisplayName(rootDocument).safeName("provider-folder")
        val lease = newRequestDirectory()
        val targetRoot = File(lease.directory, rootName).also { check(it.mkdirs()) { "Could not create provider staging folder." } }
        val totals = CopyTotals()
        runCatching { copyTree(treeUri, treeId, targetRoot, totals, depth = 0) }.getOrElse { error -> releaseStagingLease(lease.id); throw error }
        ProviderStagedItem(
            sourceUri = uri,
            displayName = rootName,
            stagedPath = targetRoot.absolutePath,
            kind = UserSelectionKind.Tree,
            filesCopied = totals.files,
            bytesCopied = totals.bytes,
            sha256 = null,
            leaseId = lease.id,
            leaseExpiresAtEpochMillis = lease.expiresAtEpochMillis,
        )
    }

    override fun releaseStagingLease(leaseId: String): Boolean {
        val id = leaseId.trim()
        if (!LEASE_ID_PATTERN.matches(id)) return false
        activeLeaseIds.remove(id)
        val dir = File(stagingRoot, id)
        File(dir, ACTIVE_LEASE_MARKER).delete()
        return dir.deleteRecursively()
    }

    override fun isStagingLeaseActive(leaseId: String): Boolean {
        val id = leaseId.trim()
        return id.isNotBlank() && (id in activeLeaseIds || File(File(stagingRoot, id), ACTIVE_LEASE_MARKER).exists())
    }

    private fun copyTree(treeUri: Uri, documentId: String, destination: File, totals: CopyTotals, depth: Int) {
        check(depth <= MAX_TREE_DEPTH) { "Provider tree exceeds the BKX staging depth limit." }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null,
            null,
            null,
        ).use { cursor ->
            if (cursor == null) return
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                check(totals.entries < MAX_TREE_ENTRIES) { "Provider tree exceeds the BKX staging entry limit." }
                totals.entries += 1
                val childId = cursor.getString(idIndex)
                val childName = cursor.getString(nameIndex).safeName("item-${totals.entries}")
                val mime = cursor.getString(mimeIndex)
                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                val childTarget = File(destination, childName)
                check(childTarget.canonicalPath.startsWith(destination.canonicalPath + File.separator)) {
                    "Provider child escaped the staging directory."
                }
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    check(childTarget.mkdir()) { "Could not create provider staging directory $childName." }
                    copyTree(treeUri, childId, childTarget, totals, depth + 1)
                } else {
                    val copied = copyDocument(childUri, childTarget)
                    totals.files += 1
                    totals.bytes += copied.bytes
                    check(totals.bytes <= MAX_STAGED_BYTES) { "Provider tree exceeds the staging size limit." }
                }
            }
        }
    }

    private fun copyDocument(uri: Uri, target: File): CopyResult {
        target.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        val input = resolver.openInputStream(uri) ?: error("Provider did not expose a readable stream for $uri")
        input.use { source ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    bytes += count
                    check(bytes <= MAX_STAGED_BYTES) { "Provider document exceeds the staging size limit." }
                    checkStagingCapacity()
                }
                output.fd.sync()
            }
        }
        return CopyResult(bytes, digest.digest().joinToString("") { "%02x".format(it) })
    }

    private fun queryDisplayName(uri: Uri): String? = resolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    ).use { cursor ->
        if (cursor != null && cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        } else null
    }

    private fun querySize(uri: Uri): Long? = resolver.query(
        uri,
        arrayOf(OpenableColumns.SIZE),
        null,
        null,
        null,
    ).use { cursor ->
        if (cursor != null && cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index).takeIf { it >= 0L } else null
        } else null
    }

    private fun persistReadGrant(uri: Uri) {
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun newRequestDirectory(): StagingLease {
        stagingRoot.mkdirs()
        pruneOldStaging()
        val id = "item-${System.currentTimeMillis()}-${System.nanoTime().toString(16)}"
        val expiresAt = System.currentTimeMillis() + STAGING_MAX_AGE_MS
        val dir = File(stagingRoot, id).also {
            check(it.mkdirs()) { "Could not create provider staging directory." }
        }
        File(dir, ACTIVE_LEASE_MARKER).writeText(expiresAt.toString())
        activeLeaseIds += id
        return StagingLease(id, dir, expiresAt)
    }

    private fun pruneOldStaging() {
        val cutoff = System.currentTimeMillis() - STAGING_MAX_AGE_MS
        stagingRoot.listFiles().orEmpty()
            .filter { file ->
                file.lastModified() in 1 until cutoff &&
                    File(file, ACTIVE_LEASE_MARKER).lastModified().let { markerTime -> markerTime == 0L || markerTime < cutoff }
            }
            .forEach { it.deleteRecursively() }
    }

    private fun checkStagingCapacity() {
        check(StatFs(stagingRoot.absolutePath).availableBytes >= STAGING_HEADROOM_BYTES) {
            "Provider staging ran out of safe free space; import stopped."
        }
    }

    private fun reserveCapacity(requiredBytes: Long) {
        stagingRoot.mkdirs()
        val stat = StatFs(stagingRoot.absolutePath)
        val requiredWithHeadroom = requiredBytes + STAGING_HEADROOM_BYTES
        check(stat.availableBytes >= requiredWithHeadroom) {
            "Provider staging needs at least $requiredWithHeadroom bytes free, but only ${stat.availableBytes} bytes are available."
        }
    }

    private fun requireContentUri(raw: String): Uri {
        require(ContentUriPathPolicy.isContentUri(raw)) { "Provider staging accepts content:// URIs only." }
        return raw.toUri()
    }

    private fun String?.safeName(fallback: String): String {
        val candidate = this.orEmpty()
            .trim()
            .map { char ->
                if (char == '/' || char == '\\' || char.code in 0..0x1F) '_' else char
            }
            .joinToString("")
            .trim('.', ' ')
        return candidate.take(MAX_NAME_CHARS).ifBlank { fallback }
    }

    private data class CopyResult(val bytes: Long, val sha256: String)
    private data class CopyTotals(var entries: Long = 0L, var files: Long = 0L, var bytes: Long = 0L)
    private data class StagingLease(val id: String, val directory: File, val expiresAtEpochMillis: Long)

    private companion object {
        const val COPY_BUFFER_BYTES = 1024 * 1024
        const val MAX_TREE_DEPTH = 64
        const val MAX_TREE_ENTRIES = 100_000L
        const val MAX_STAGED_BYTES = 8L * 1024L * 1024L * 1024L
        val LEASE_ID_PATTERN = Regex("item-[0-9]+-[0-9a-f]+")
        const val MAX_NAME_CHARS = 180
        const val STAGING_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        const val STAGING_HEADROOM_BYTES = 32L * 1024L * 1024L
        const val MIN_UNKNOWN_STREAM_RESERVE_BYTES = 64L * 1024L * 1024L
        const val MIN_UNKNOWN_TREE_RESERVE_BYTES = 512L * 1024L * 1024L
        const val ACTIVE_LEASE_MARKER = ".bkx-active-lease"
    }
}
