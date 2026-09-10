package com.mikeyphw.bkx.android.core.repositories

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SnapshotDescriptor(
    val id: String,
    @SerialName("short_id") val shortId: String = "",
    val time: String,
    val hostname: String = "",
    val username: String = "",
    val paths: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val size: ULong? = null,
) {
    init {
        require(id.isNotBlank()) { "snapshot id must not be blank" }
        require(time.isNotBlank()) { "snapshot time must not be blank" }
    }
}

enum class SnapshotOverwriteMode(val wireValue: String) {
    Always("always"),
    IfChanged("if-changed"),
    IfNewer("if-newer"),
    Never("never"),
}
