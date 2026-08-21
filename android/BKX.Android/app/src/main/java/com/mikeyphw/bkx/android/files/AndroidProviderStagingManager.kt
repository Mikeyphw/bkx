package com.mikeyphw.bkx.android.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.net.toUri
import com.mikeyphw.bkx.android.core.files.CONTENT_URI_PREFIX
import com.mikeyphw.bkx.android.core.files.ProviderStagedItem
import com.mikeyphw.bkx.android.core.files.ProviderStagingPort
import com.mikeyphw.bkx.android.core.files.UserSelectionKind
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Streams content:// resources into app-private cache paths for the exceptional
 * case where a provider (Drive, DocumentsProvider, etc.) has no direct path.
 * Direct filesystem/root paths remain the primary BKX storage architecture.
 */
class AndroidProviderStagingManager(
    context: Context,
) : ProviderStagingPort {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val stagingRoot = File(appContext.cacheDir, "bkx/provider-staging")

    override suspend fun stageDocument(uri: String): ProviderStagedItem = withContext(Dispatchers.IO) {
        val parsed = requireContentUri(uri)
        persistReadGrant(parsed)
        val name = queryDisplayName(parsed).safeName("provider-file")
        val requestDir = newRequestDirectory()
        val target = File(requestDir, name)
        val copy = copyDocument(parsed, target)
        ProviderStagedItem(uri, name, target.absolutePath, UserSelectionKind.Document, 1, copy.bytes, copy.sha256)
    }

    override suspend fun stageTree(uri: String): ProviderStagedItem = withContext(Dispatchers.IO) {
        val treeUri = requireContentUri(uri)
        persistReadGrant(treeUri)
        val treeId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootDocument = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId)
        val rootName = queryDisplayName(rootDocument).safeName("provider-folder")
        val requestDir = newRequestDirectory()
        val targetRoot = File(requestDir, rootName).also { check(it.mkdirs()) { "Could not create provider staging folder." } }
        val totals = CopyTotals()
        copyTree(treeUri, treeId, targetRoot, totals, depth = 0)
        ProviderStagedItem(uri, rootName, targetRoot.absolutePath, UserSelectionKind.Tree, totals.files, totals.bytes)
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

    private fun persistReadGrant(uri: Uri) {
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun newRequestDirectory(): File {
        stagingRoot.mkdirs()
        pruneOldStaging()
        return File(stagingRoot, "item-${System.currentTimeMillis()}-${System.nanoTime().toString(16)}").also {
            check(it.mkdirs()) { "Could not create provider staging directory." }
        }
    }

    private fun pruneOldStaging() {
        val cutoff = System.currentTimeMillis() - STAGING_MAX_AGE_MS
        stagingRoot.listFiles().orEmpty()
            .filter { it.lastModified() in 1 until cutoff }
            .forEach { it.deleteRecursively() }
    }

    private fun requireContentUri(raw: String): Uri {
        require(raw.startsWith(CONTENT_URI_PREFIX)) { "Provider staging accepts content:// URIs only." }
        return raw.toUri()
    }

    private fun String?.safeName(fallback: String): String {
        val candidate = this.orEmpty().trim().replace(Regex("[\\/\\u0000-\\u001f]"), "_").trim('.', ' ')
        return candidate.take(MAX_NAME_CHARS).ifBlank { fallback }
    }

    private data class CopyResult(val bytes: Long, val sha256: String)
    private data class CopyTotals(var entries: Long = 0L, var files: Long = 0L, var bytes: Long = 0L)

    private companion object {
        const val COPY_BUFFER_BYTES = 1024 * 1024
        const val MAX_TREE_DEPTH = 64
        const val MAX_TREE_ENTRIES = 100_000L
        const val MAX_NAME_CHARS = 180
        const val STAGING_MAX_AGE_MS = 24L * 60L * 60L * 1000L
    }
}
