package com.mikeyphw.bkx.android.app.store

import android.content.Context
import androidx.core.content.edit
import com.mikeyphw.bkx.android.core.operations.OperationRuntimeState
import com.mikeyphw.bkx.android.core.operations.OperationStateStore
import com.mikeyphw.bkx.android.core.runs.RunSummary
import com.mikeyphw.bkx.android.core.runs.RunSummaryStore
import com.mikeyphw.bkx.android.core.settings.BkxSettings
import com.mikeyphw.bkx.android.core.settings.SettingsStore
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SharedPreferencesRunSummaryStore(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : RunSummaryStore {
    private val prefs = context.applicationContext.getSharedPreferences("bkx_run_summaries", Context.MODE_PRIVATE)

    override suspend fun recentRuns(limit: Int): List<RunSummary> = readAll()
        .sortedByDescending { it.startedAtEpochMillis }
        .take(limit)

    override suspend fun upsert(summary: RunSummary) {
        val next = (readAll().filterNot { it.requestId == summary.requestId } + summary)
            .sortedByDescending { it.startedAtEpochMillis }
            .take(MAX_STORED_RUNS)
        prefs.edit { putString(KEY_RUNS, json.encodeToString(ListSerializer(RunSummary.serializer()), next)) }
    }

    private fun readAll(): List<RunSummary> {
        val raw = prefs.getString(KEY_RUNS, null) ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(RunSummary.serializer()), raw) }.getOrDefault(emptyList())
    }

    private companion object {
        const val KEY_RUNS = "runs"
        const val MAX_STORED_RUNS = 50
    }
}

class SharedPreferencesSettingsStore(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : SettingsStore {
    private val prefs = context.applicationContext.getSharedPreferences("bkx_settings", Context.MODE_PRIVATE)

    override suspend fun read(): BkxSettings {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return BkxSettings()
        return runCatching { json.decodeFromString<BkxSettings>(raw) }.getOrDefault(BkxSettings())
    }

    override suspend fun update(settings: BkxSettings) {
        prefs.edit { putString(KEY_SETTINGS, json.encodeToString(settings)) }
    }

    private companion object {
        const val KEY_SETTINGS = "settings"
    }
}


class SharedPreferencesOperationStateStore(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : OperationStateStore {
    private val prefs = context.applicationContext.getSharedPreferences("bkx_active_operations", Context.MODE_PRIVATE)

    override suspend fun read(): OperationRuntimeState {
        val raw = prefs.getString(KEY_STATE, null) ?: return OperationRuntimeState()
        return runCatching { json.decodeFromString<OperationRuntimeState>(raw) }.getOrDefault(OperationRuntimeState())
    }

    override suspend fun update(state: OperationRuntimeState) {
        prefs.edit { putString(KEY_STATE, json.encodeToString(state)) }
    }

    private companion object {
        const val KEY_STATE = "operation_runtime_state"
    }
}
