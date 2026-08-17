package com.mikeyphw.bkx.android.core.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class BackendPreference {
    @SerialName("termux_run_command") TermuxRunCommand,
}

@Serializable
enum class LogPrivacyMode {
    @SerialName("private") Private,
    @SerialName("diagnostic") Diagnostic,
}

@Serializable
enum class ProgressPreference {
    @SerialName("polling") Polling,
    @SerialName("compact") Compact,
}

@Serializable
data class BkxSettings(
    val backend: BackendPreference = BackendPreference.TermuxRunCommand,
    val stagingLocationLabel: String = "Termux shared staging area",
    val logPrivacyMode: LogPrivacyMode = LogPrivacyMode.Private,
    val progressPreference: ProgressPreference = ProgressPreference.Polling,
)

interface SettingsStore {
    suspend fun read(): BkxSettings
    suspend fun update(settings: BkxSettings)
}

class InMemorySettingsStore(
    private var settings: BkxSettings = BkxSettings(),
) : SettingsStore {
    override suspend fun read(): BkxSettings = settings

    override suspend fun update(settings: BkxSettings) {
        this.settings = settings
    }
}
