package com.mikeyphw.bkx.android.automation

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class BkxTaskerConditionSnapshot(
    val activeSignals: Map<String, BkxTaskerSignal> = emptyMap(),
    val terminalHistory: List<BkxTaskerSignal> = emptyList(),
    val remoteAvailability: Map<String, Boolean> = emptyMap(),
    val driveState: BkxTaskerDriveState = BkxTaskerDriveState.Unknown,
    val lastProgressBucket: Map<String, Int> = emptyMap(),
)

interface BkxTaskerConditionStore {
    fun read(): BkxTaskerConditionSnapshot
    fun update(transform: (BkxTaskerConditionSnapshot) -> BkxTaskerConditionSnapshot): BkxTaskerConditionSnapshot
}

class SharedPreferencesBkxTaskerConditionStore(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : BkxTaskerConditionStore {
    private val prefs = (context.applicationContext ?: context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun read(): BkxTaskerConditionSnapshot = prefs.getString(KEY, null)?.let { raw ->
        runCatching { json.decodeFromString<BkxTaskerConditionSnapshot>(raw) }.getOrNull()
    } ?: BkxTaskerConditionSnapshot()

    override fun update(transform: (BkxTaskerConditionSnapshot) -> BkxTaskerConditionSnapshot): BkxTaskerConditionSnapshot {
        synchronized(this) {
            val next = transform(read()).bounded()
            prefs.edit(commit = true) { putString(KEY, json.encodeToString(next)) }
            return next
        }
    }

    private fun BkxTaskerConditionSnapshot.bounded(): BkxTaskerConditionSnapshot = copy(
        activeSignals = activeSignals.entries.toList().takeLast(MAX_PROGRESS_IDS).associate { it.toPair() },
        terminalHistory = terminalHistory
            .distinctBy { it.requestId.ifBlank { "${it.event}:${it.timestampEpochMillis}" } }
            .sortedByDescending { it.timestampEpochMillis }
            .take(MAX_TERMINALS),
        lastProgressBucket = lastProgressBucket.entries.toList().takeLast(MAX_PROGRESS_IDS).associate { it.toPair() },
    )

    private companion object {
        const val PREFS = "bkx_tasker_conditions"
        const val KEY = "snapshot_v1"
        const val MAX_TERMINALS = 40
        const val MAX_PROGRESS_IDS = 64
    }
}

internal object BkxTaskerConditionMatcher {
    fun eventMatches(input: BkxTaskerEventInput, signal: BkxTaskerSignal): Boolean {
        val selected = BkxTaskerEventType.fromId(input.eventType)
        if (selected != BkxTaskerEventType.Any && selected.id != signal.event) return false
        if (!commonMatches(input.operation, input.remoteFilter, input.remoteName, input.requestIdPattern, input.errorCode, signal)) return false
        if (signal.event == BkxTaskerEventType.ProgressMilestone.id && !progressMatches(input.progressCadence, signal.progressPercent)) return false
        return true
    }

    fun stateSignalMatches(input: BkxTaskerStateInput, signal: BkxTaskerSignal): Boolean =
        commonMatches(input.operation, input.remoteFilter, input.remoteName, input.requestIdPattern, input.errorCode, signal)

    fun operationMatches(filter: String, operation: String): Boolean = filter.trim().let { wanted ->
        wanted.isBlank() || globMatches(wanted, operation)
    }

    fun requestMatches(pattern: String, requestId: String): Boolean = pattern.trim().let { wanted ->
        wanted.isBlank() || globMatches(wanted, requestId)
    }

    fun remoteMatches(filter: String, remoteName: String, remotes: List<String>): Boolean = when (filter.trim().lowercase()) {
        "", "any" -> true
        "local" -> remotes.isEmpty() || remotes.all { it == "local" }
        "remote" -> remotes.any { it != "local" }
        "google_drive", "google-drive" -> "google-drive" in remotes
        "rclone" -> remotes.any { it.startsWith("rclone:") && (remoteName.isBlank() || it.removePrefix("rclone:").equals(remoteName.trim(), true)) }
        else -> remotes.any { it.equals(filter.trim(), true) }
    }

    fun progressMatches(cadence: String, progress: Int?): Boolean {
        val value = progress ?: return false
        return when (cadence.trim().lowercase()) {
            "every_5", "5" -> value % 5 == 0
            "every_10", "10" -> value % 10 == 0
            "every_25", "25" -> value % 25 == 0
            "every_50", "50" -> value % 50 == 0
            else -> value in setOf(0, 10, 25, 50, 75, 90, 100)
        }
    }

    fun canonicalProgressBucket(progress: Double?): Int? = progress?.let {
        ((it.coerceIn(0.0, 1.0) * 100.0).toInt() / 5) * 5
    }

    private fun commonMatches(
        operation: String,
        remoteFilter: String,
        remoteName: String,
        requestPattern: String,
        errorCode: String,
        signal: BkxTaskerSignal,
    ): Boolean {
        if (!operationMatches(operation, signal.operation)) return false
        if (!requestMatches(requestPattern, signal.requestId)) return false
        if (!remoteMatches(remoteFilter, remoteName, signal.remotes)) return false
        if (errorCode.isNotBlank() && !globMatches(errorCode.trim(), signal.errorCode)) return false
        return true
    }

    private fun globMatches(glob: String, value: String): Boolean {
        val regex = buildString {
            append('^')
            glob.forEach { ch ->
                when (ch) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(ch.toString()))
                }
            }
            append('$')
        }.toRegex(RegexOption.IGNORE_CASE)
        return regex.matches(value)
    }
}
