package com.mikeyphw.bkx.android.app.navigation

import com.mikeyphw.bkx.android.core.backend.BackendReadiness

enum class BkxDestination(val label: String, val shortLabel: String) {
    Home("Home", "Home"),
    Backup("Backup", "Backup"),
    Archives("Archives & restore", "Archives"),
    Activity("Activity", "Activity"),
    More("More", "More"),
}

sealed interface ScreenStatus {
    data object Loading : ScreenStatus
    data class Empty(val message: String) : ScreenStatus
    data class Error(val title: String, val message: String) : ScreenStatus
    data object Content : ScreenStatus
}

data class BackendToolState(
    val readiness: BackendReadiness,
    val runtimeLabel: String = "Embedded BKX / libsu root when granted",
    val protocolVersion: Int,
    val bkxVersion: String? = null,
    val capabilities: List<String> = emptyList(),
    val remediationActions: List<SetupRemediationAction> = readiness.toSetupActions(),
)

data class SetupRemediationAction(
    val code: String,
    val title: String,
    val steps: List<String>,
)

fun BackendReadiness.toSetupActions(): List<SetupRemediationAction> = when (this) {
    BackendReadiness.Checking -> listOf(
        SetupRemediationAction("wait_for_probe", "Checking BKX", listOf("The embedded engine check is still running.")),
    )
    is BackendReadiness.Ready -> emptyList()
    else -> listOf(
        SetupRemediationAction(code = code, title = title, steps = remediationSteps),
    )
}


enum class ArchiveDetailSection { Overview, Contents, Restore }

enum class MoreRoute { Overview, GoogleDrive, Remotes, Transfer, Diagnostics }
