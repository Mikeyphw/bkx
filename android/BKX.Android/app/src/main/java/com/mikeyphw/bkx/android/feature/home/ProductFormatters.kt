package com.mikeyphw.bkx.android.feature.home

import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

fun formatBytes(bytes: Long?): String {
    if (bytes == null) return "—"
    val value = abs(bytes.toDouble())
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var unit = 0
    var scaled = value
    while (scaled >= 1024.0 && unit < units.lastIndex) {
        scaled /= 1024.0
        unit += 1
    }
    val prefix = if (bytes < 0) "-" else ""
    return if (unit == 0) "$prefix${scaled.toLong()} ${units[unit]}" else "$prefix${"%.1f".format(scaled)} ${units[unit]}"
}

fun formatTime(epochMillis: Long?): String = if (epochMillis == null) {
    "—"
} else {
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMillis))
}

fun operationLabel(operation: String): String = when (operation) {
    "backup", "backup.create", "backup.snapshot" -> "Backup"
    "archive.verify" -> "Archive verification"
    "restore.extract" -> "Restore"
    "transfer", "transfer.run" -> "Transfer"
    "backup.plan" -> "Backup review"
    "restore.preview" -> "Restore review"
    "transfer.plan" -> "Transfer review"
    else -> operation.substringAfterLast('.').replace('_', ' ').replaceFirstChar { it.uppercase() }
}
