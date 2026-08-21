package com.mikeyphw.bkx.android.core.files

/** Result of materializing provider-only content into BKX's private filesystem. */
data class ProviderStagedItem(
    val sourceUri: String,
    val displayName: String,
    val stagedPath: String,
    val kind: UserSelectionKind,
    val filesCopied: Long,
    val bytesCopied: Long,
    val sha256: String? = null,
) {
    init {
        require(sourceUri.startsWith(CONTENT_URI_PREFIX))
        require(displayName.isNotBlank())
        require(stagedPath.startsWith("/"))
        require(filesCopied >= 0L)
        require(bytesCopied >= 0L)
    }
}

/** Secondary fallback for DocumentsProvider resources which have no real path. */
interface ProviderStagingPort {
    suspend fun stageDocument(uri: String): ProviderStagedItem
    suspend fun stageTree(uri: String): ProviderStagedItem
}
