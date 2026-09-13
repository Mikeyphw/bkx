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
    val leaseId: String,
    val leaseExpiresAtEpochMillis: Long,
) {
    init {
        require(ContentUriPathPolicy.isContentUri(sourceUri))
        require(displayName.isNotBlank())
        require(stagedPath.startsWith("/"))
        require(!ContentUriPathPolicy.isContentUri(stagedPath))
        require(filesCopied >= 0L)
        require(bytesCopied >= 0L)
        require(leaseId.isNotBlank())
        require(leaseExpiresAtEpochMillis > 0L)
    }
}

/** Secondary fallback for DocumentsProvider resources which have no real path. */
interface ProviderStagingPort {
    suspend fun stageDocument(uri: String): ProviderStagedItem
    suspend fun stageTree(uri: String): ProviderStagedItem
    fun releaseStagingLease(leaseId: String): Boolean = false
    fun isStagingLeaseActive(leaseId: String): Boolean = false
}
