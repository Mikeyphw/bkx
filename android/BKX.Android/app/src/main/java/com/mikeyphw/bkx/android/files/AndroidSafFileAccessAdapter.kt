package com.mikeyphw.bkx.android.files

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.mikeyphw.bkx.android.core.files.CONTENT_URI_PREFIX
import com.mikeyphw.bkx.android.core.files.FileAccessIssue
import com.mikeyphw.bkx.android.core.files.PersistedUriGrantRecord
import com.mikeyphw.bkx.android.core.files.SafFileAccessPort
import com.mikeyphw.bkx.android.core.files.SafGrantRequest
import com.mikeyphw.bkx.android.core.files.StagedFilePlanner
import com.mikeyphw.bkx.android.core.files.StagedFileRecord
import com.mikeyphw.bkx.android.core.files.StagingEvidence
import com.mikeyphw.bkx.android.core.files.UriGrantState
import com.mikeyphw.bkx.android.core.files.UserFileSelection

class AndroidSafFileAccessAdapter(
    private val context: Context,
    private val planner: StagedFilePlanner = StagedFilePlanner(),
) : SafFileAccessPort {
    override fun persistGrant(selection: SafGrantRequest): PersistedUriGrantRecord {
        val flags = selection.toTakePersistableFlags()
        context.contentResolver.takePersistableUriPermission(Uri.parse(selection.uri), flags)
        return PersistedUriGrantRecord(
            uri = selection.uri,
            kind = selection.kind,
            read = selection.read,
            write = selection.write,
            state = UriGrantState.Active,
            persistedAtEpochMillis = selection.persistedAtEpochMillis,
        )
    }

    override fun validateGrant(grant: PersistedUriGrantRecord): FileAccessIssue? {
        if (grant.state != UriGrantState.Active) return FileAccessIssue.GrantLost(grant.uri)
        val active = context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri.toString() == grant.uri &&
                (!grant.read || permission.isReadPermission) &&
                (!grant.write || permission.isWritePermission)
        }
        return if (active) null else FileAccessIssue.GrantLost(grant.uri)
    }

    override fun planStaging(
        selection: UserFileSelection,
        stagingPath: String,
        evidence: StagingEvidence,
    ): StagedFileRecord = planner.plan(selection, stagingPath, evidence)

    fun readMetadata(uriString: String): AndroidContentMetadata {
        require(uriString.startsWith(CONTENT_URI_PREFIX)) { "Metadata can be read only from content:// URIs." }
        val uri = Uri.parse(uriString)
        context.contentResolver.query(uri, null, null, null, null).use { cursor ->
            return AndroidContentMetadata(
                displayName = cursor?.readString(OpenableColumns.DISPLAY_NAME),
                sizeBytes = cursor?.readLong(OpenableColumns.SIZE),
            )
        }
    }
}

data class AndroidContentMetadata(
    val displayName: String?,
    val sizeBytes: Long?,
)

object SafIntentFactory {
    fun openDocument(mimeTypes: Array<String> = arrayOf("*/*")): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = if (mimeTypes.size == 1) mimeTypes.single() else "*/*"
        putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }

    fun openTree(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
        )
    }
}

private fun SafGrantRequest.toTakePersistableFlags(): Int {
    var flags = 0
    if (read) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
    if (write) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    return flags
}

private fun Cursor.readString(columnName: String): String? {
    val index = getColumnIndex(columnName)
    return if (index >= 0 && moveToFirst()) getString(index) else null
}

private fun Cursor.readLong(columnName: String): Long? {
    val index = getColumnIndex(columnName)
    return if (index >= 0 && moveToFirst() && !isNull(index)) getLong(index) else null
}
