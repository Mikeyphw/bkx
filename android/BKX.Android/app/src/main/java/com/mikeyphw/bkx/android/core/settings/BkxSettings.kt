package com.mikeyphw.bkx.android.core.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class RootAccessMode {
    @SerialName("preferred") Preferred,
    @SerialName("required") Required,
    @SerialName("disabled") Disabled,
}

@Serializable
enum class LogPrivacyMode {
    @SerialName("private") Private,
    @SerialName("diagnostic") Diagnostic,
}

@Serializable
enum class ProgressPreference {
    @SerialName("event_driven") EventDriven,
    @SerialName("polling") Polling,
    @SerialName("compact") Compact,
}

@Serializable
enum class AppearancePreference {
    @SerialName("system") System,
    @SerialName("light") Light,
    @SerialName("dark") Dark,
}

const val BKX_SETTINGS_SCHEMA_VERSION = 1

@Serializable
data class BkxSettings(
    @SerialName("schema_version") val schemaVersion: Int = BKX_SETTINGS_SCHEMA_VERSION,
    val rootAccessMode: RootAccessMode = RootAccessMode.Preferred,
    val storageLocationLabel: String = "Direct filesystem access",
    val logPrivacyMode: LogPrivacyMode = LogPrivacyMode.Private,
    val progressPreference: ProgressPreference = ProgressPreference.EventDriven,
    val appearance: AppearancePreference = AppearancePreference.System,
    val dynamicColor: Boolean = true,
) {
    init {
        require(schemaVersion == BKX_SETTINGS_SCHEMA_VERSION) { "settings schema_version must be $BKX_SETTINGS_SCHEMA_VERSION" }
    }
}

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
