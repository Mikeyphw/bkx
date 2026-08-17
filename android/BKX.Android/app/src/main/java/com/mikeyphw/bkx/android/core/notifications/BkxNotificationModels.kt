
package com.mikeyphw.bkx.android.core.notifications

import com.mikeyphw.bkx.android.core.operations.ActiveOperation
import com.mikeyphw.bkx.android.core.operations.ActiveOperationStatus
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
        val text = if (policy.privacyMode) {
            "${operation.status.name.lowercase().replaceFirstChar { it.uppercase() }} • ${operation.lastPhase}"
        } else {
            val shortId = operation.requestId.take(12)
            "${operation.status.name.lowercase().replaceFirstChar { it.uppercase() }} • ${operation.lastPhase} • $shortId"
        }
        return BkxOperationNotificationDecision.Show(
            BkxOperationNotificationPlan(
                requestId = operation.requestId,
                title = "BKX ${operation.operation}",
                text = text,
                ongoing = ongoing,
                allowCancelAction = operation.cancelFilePath != null && ongoing,
                progressPercent = progressPercent,
                redacted = policy.privacyMode,
            ),
        )
    }
}
