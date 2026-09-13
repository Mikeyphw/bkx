package com.mikeyphw.bkx.android.core.operations

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Classifies the operation that Android must protect, not merely the outer
 * bridge verb. Indirect UI surfaces such as Saved Jobs and Run Again execute
 * long backup/transfer/restore work under wrapper verbs, so foreground,
 * cancellation and notification policy must use this effective operation.
 */
object OperationClassifier {
    private val foregroundOperations = setOf(
        "backup.create",
        "backup.snapshot",
        "archive.verify",
        "restore.extract",
        "transfer.run",
        "repository.init",
        "repository.check",
        "repository.prune",
        "retention.apply",
        "snapshots.restore",
        "snapshots.forget",
    )
    private val wrapperOperations = setOf("jobs.run", "runs.replay")
    private val activeStatuses = setOf(
        ActiveOperationStatus.Queued,
        ActiveOperationStatus.Running,
        ActiveOperationStatus.Cancelling,
    )
    private val terminalStatuses = setOf(
        ActiveOperationStatus.Success,
        ActiveOperationStatus.Failure,
        ActiveOperationStatus.Cancelled,
        ActiveOperationStatus.Interrupted,
    )

    fun effectiveOperation(operation: String, payload: JsonObject? = null): String {
        if (operation in foregroundOperations) return operation
        if (operation !in wrapperOperations) return operation
        val explicit = payload?.string("effective_operation")
            ?: payload?.string("effective_operation_hint")
            ?: payload?.string("operation_kind")
        if (explicit != null && explicit in foregroundOperations) return explicit
        return when (payload?.string("kind")?.lowercase()) {
            "backup", "backup_snapshot", "snapshot" -> "backup.snapshot"
            "transfer" -> "transfer.run"
            "restore", "snapshot_restore" -> "snapshots.restore"
            else -> operation
        }
    }

    fun requiresForeground(operation: String): Boolean = operation in foregroundOperations

    fun requiresForeground(active: ActiveOperation): Boolean = requiresForeground(active.effectiveOperation)

    fun isActive(status: ActiveOperationStatus): Boolean = status in activeStatuses

    fun isTerminal(status: ActiveOperationStatus): Boolean = status in terminalStatuses

    private fun JsonObject.string(key: String): String? = runCatching {
        this[key]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }.getOrNull()
}
