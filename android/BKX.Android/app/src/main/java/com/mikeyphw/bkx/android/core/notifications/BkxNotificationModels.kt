
package com.mikeyphw.bkx.android.core.notifications

import com.mikeyphw.bkx.android.core.operations.ActiveOperation
import com.mikeyphw.bkx.android.core.operations.ActiveOperationStatus
import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class BkxNotificationPermissionStatus {
    @SerialName("unknown") Unknown,
    @SerialName("not_required") NotRequired,
    @SerialName("granted") Granted,
    @SerialName("denied") Denied,
}

@Serializable
data class OperationNotificationPolicy(
    val enabled: Boolean = true,
    @SerialName("privacy_mode") val privacyMode: Boolean = true,
) {
    val redactsSensitiveText: Boolean = privacyMode
}

@Serializable
data class BkxOperationNotificationPlan(
    @SerialName("channel_id") val channelId: String = BkxOperationNotificationChannels.OPERATIONS,
    @SerialName("notification_id") val notificationId: Int = BkxNotificationIds.OPERATION_FOREGROUND,
    @SerialName("request_id") val requestId: String,
    val title: String,
    val text: String,
    val ongoing: Boolean,
    @SerialName("allow_cancel_action") val allowCancelAction: Boolean,
    @SerialName("progress_percent") val progressPercent: Int? = null,
    @SerialName("redacted") val redacted: Boolean = true,
) {
    init {
        require(requestId.isNotBlank()) { "notification request id must not be blank" }
        require(title.isNotBlank()) { "notification title must not be blank" }
        require(text.isNotBlank()) { "notification text must not be blank" }
        require(progressPercent == null || progressPercent in 0..100) { "notification progress must be 0..100" }
    }
}

sealed interface BkxOperationNotificationDecision {
    data class Show(val plan: BkxOperationNotificationPlan) : BkxOperationNotificationDecision
    data class Blocked(val code: String, val userAction: String) : BkxOperationNotificationDecision
}

object BkxOperationNotificationChannels {
    const val OPERATIONS = "bkx_operations"
}

object BkxNotificationIds {
    const val OPERATION_FOREGROUND = 61001
    private const val TERMINAL_BASE = 62000

    fun terminalFor(requestId: String): Int = TERMINAL_BASE + (requestId.hashCode() and 0x0fff)
}

object BkxOperationNotificationPlanner {
    fun plan(
        operation: ActiveOperation,
        permissionStatus: BkxNotificationPermissionStatus,
        policy: OperationNotificationPolicy = OperationNotificationPolicy(),
    ): BkxOperationNotificationDecision {
        if (!policy.enabled) {
            return BkxOperationNotificationDecision.Blocked(
                code = "notifications_disabled",
                userAction = "Enable operation notifications in BKX Android settings.",
            )
        }
        if (permissionStatus == BkxNotificationPermissionStatus.Denied || permissionStatus == BkxNotificationPermissionStatus.Unknown) {
            return BkxOperationNotificationDecision.Blocked(
                code = "notification_permission_required",
                userAction = "Grant Android notification permission before starting a user-visible BKX operation.",
            )
        }
        val ongoing = operation.status in setOf(
            ActiveOperationStatus.Queued,
            ActiveOperationStatus.Running,
            ActiveOperationStatus.Cancelling,
        )
        val progressPercent = operation.progress?.let { (it * 100).toInt().coerceIn(0, 100) }
        val detail = buildList {
            if (operation.filesDone != null) {
                add(
                    if (operation.filesTotal != null && operation.filesTotal > 0) {
                        "${operation.filesDone}/${operation.filesTotal} files"
                    } else {
                        "${operation.filesDone} files"
                    },
                )
            }
            if (operation.bytesDone != null) {
                add(
                    if (operation.bytesTotal != null && operation.bytesTotal > 0) {
                        "${compactBytes(operation.bytesDone)}/${compactBytes(operation.bytesTotal)}"
                    } else {
                        compactBytes(operation.bytesDone)
                    },
                )
            }
        }.joinToString(" • ")
        val base = "${operation.status.name.lowercase().replaceFirstChar { it.uppercase() }} • ${operation.lastPhase}"
        val text = buildString {
            append(base)
            if (detail.isNotBlank()) append(" • ").append(detail)
            if (!policy.privacyMode) append(" • ").append(operation.requestId.take(12))
        }
        return BkxOperationNotificationDecision.Show(
            BkxOperationNotificationPlan(
                requestId = operation.requestId,
                title = "BKX ${displayOperation(operation.operation)}",
                text = text,
                ongoing = ongoing,
                allowCancelAction = operation.cancelFilePath != null && ongoing,
                progressPercent = progressPercent,
                redacted = policy.privacyMode,
            ),
        )
    }

    private fun displayOperation(operation: String): String = when (operation) {
        "backup.create" -> "backup"
        "backup.snapshot" -> "snapshot backup"
        "repository.init" -> "repository setup"
        "archive.verify" -> "archive verification"
        "restore.extract" -> "restore"
        "transfer.run" -> "transfer"
        else -> "operation"
    }

    private fun compactBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
