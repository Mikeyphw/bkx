package com.mikeyphw.bkx.android.automation

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AutomationAccessStore(context: Context) {
    private val appContext = context.applicationContext ?: context
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    data class Snapshot(val enabled: Boolean, val token: String, val taskerToken: String)

    fun snapshot(): Snapshot = Snapshot(
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        token = preferences.getString(KEY_TOKEN, "").orEmpty(),
        taskerToken = preferences.getString(KEY_TASKER_TOKEN, "").orEmpty(),
    )

    fun ensureEnabled(): Snapshot {
        val current = snapshot()
        if (current.enabled && current.token.isNotBlank() && current.taskerToken.isNotBlank()) return current
        val token = current.token.ifBlank(::newToken)
        val taskerToken = current.taskerToken.ifBlank(::newToken)
        preferences.edit {
            putBoolean(KEY_ENABLED, true)
            putString(KEY_TOKEN, token)
            putString(KEY_TASKER_TOKEN, taskerToken)
        }
        return Snapshot(true, token, taskerToken)
    }

    fun setEnabled(enabled: Boolean): Snapshot {
        val current = snapshot()
        val token = current.token.ifBlank(::newToken)
        val taskerToken = current.taskerToken.ifBlank(::newToken)
        preferences.edit {
            putBoolean(KEY_ENABLED, enabled)
            putString(KEY_TOKEN, token)
            putString(KEY_TASKER_TOKEN, taskerToken)
        }
        if (!enabled) {
            AutomationDispatchStore(appContext).clear()
            SharedPreferencesAutomationStateStore(appContext).clearActiveRequests()
        }
        return Snapshot(enabled, token, taskerToken)
    }

    fun regenerate(): Snapshot {
        val token = newToken()
        val taskerToken = newToken()
        preferences.edit {
            putBoolean(KEY_ENABLED, true)
            putString(KEY_TOKEN, token)
            putString(KEY_TASKER_TOKEN, taskerToken)
        }
        return Snapshot(true, token, taskerToken)
    }

    fun isAuthorized(candidate: String?): Boolean {
        val current = snapshot()
        if (!current.enabled || current.token.isBlank() || candidate.isNullOrBlank()) return false
        return MessageDigest.isEqual(current.token.toByteArray(Charsets.UTF_8), candidate.toByteArray(Charsets.UTF_8))
    }

    fun isTaskerAuthorized(candidate: String?): Boolean {
        val current = snapshot()
        if (!current.enabled || current.taskerToken.isBlank() || candidate.isNullOrBlank()) return false
        return MessageDigest.isEqual(current.taskerToken.toByteArray(Charsets.UTF_8), candidate.toByteArray(Charsets.UTF_8))
    }

    private fun newToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
    }

    private companion object {
        const val PREFERENCES_NAME = "bkx_automation_access"
        const val KEY_ENABLED = "enabled"
        const val KEY_TOKEN = "capability_token"
        const val KEY_TASKER_TOKEN = "tasker_capability_token"
    }
}

interface AutomationStatePort {
    fun terminal(requestId: String): AutomationResult?
    fun activeStage(requestId: String): String?
    fun markActive(requestId: String, operationRequestId: String)
    fun clearActive(requestId: String)
    fun complete(result: AutomationResult)
}

class SharedPreferencesAutomationStateStore(context: Context) : AutomationStatePort {
    private val preferences = (context.applicationContext ?: context).getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override fun terminal(requestId: String): AutomationResult? = preferences.getString("terminal:$requestId", null)?.let { encoded ->
        runCatching { json.decodeFromString<AutomationResult>(encoded) }.getOrNull()
    }

    override fun activeStage(requestId: String): String? = preferences.getString("active:$requestId", null)

    override fun markActive(requestId: String, operationRequestId: String) {
        preferences.edit { putString("active:$requestId", operationRequestId) }
    }

    override fun clearActive(requestId: String) {
        preferences.edit(commit = true) { remove("active:$requestId") }
    }

    fun clearActiveRequests(): Int {
        val activeKeys = preferences.all.keys.filter { it.startsWith("active:") }
        preferences.edit(commit = true) { activeKeys.forEach { remove(it) } }
        return activeKeys.size
    }

    override fun complete(result: AutomationResult) {
        val existing = preferences.getString(KEY_INDEX, "").orEmpty().lineSequence().filter(String::isNotBlank).toMutableList()
        existing.remove(result.requestId)
        existing.add(0, result.requestId)
        val retained = existing.take(MAX_TERMINAL_RESULTS)
        preferences.edit {
            remove("active:${result.requestId}")
            putString("terminal:${result.requestId}", json.encodeToString(result))
            putString(KEY_INDEX, retained.joinToString("\n"))
            existing.drop(MAX_TERMINAL_RESULTS).forEach { old -> remove("terminal:$old") }
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "bkx_automation_state"
        const val KEY_INDEX = "terminal_index"
        const val MAX_TERMINAL_RESULTS = 50
    }
}

class InMemoryAutomationStateStore : AutomationStatePort {
    private val terminals = linkedMapOf<String, AutomationResult>()
    private val active = linkedMapOf<String, String>()
    override fun terminal(requestId: String): AutomationResult? = terminals[requestId]
    override fun activeStage(requestId: String): String? = active[requestId]
    override fun markActive(requestId: String, operationRequestId: String) { active[requestId] = operationRequestId }
    override fun clearActive(requestId: String) { active.remove(requestId) }
    override fun complete(result: AutomationResult) { active.remove(result.requestId); terminals[result.requestId] = result }
}
