package com.mikeyphw.bkx.android.app.navigation

import com.mikeyphw.bkx.android.core.backend.BackendReadiness
import com.mikeyphw.bkx.android.termux.TermuxRunCommandContract

enum class BkxDestination(val label: String) {
    Home("Home"),
    Backup("Backup"),
    Archives("Archives"),
    Restore("Restore"),
    Transfers("Transfers"),
    Tools("Tools"),
    Settings("Settings"),
}

sealed interface ScreenStatus {
    data object Loading : ScreenStatus
    data class Empty(val message: String) : ScreenStatus
    data class Error(val title: String, val message: String) : ScreenStatus
    data object Content : ScreenStatus
}

data class BackendToolState(
    val readiness: BackendReadiness,
    val wrapperPath: String = TermuxRunCommandContract.APPROVED_WRAPPER_PATH,
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
        SetupRemediationAction("wait_for_probe", "Wait for backend probe", listOf("Let the readiness check finish.")),
    )
    is BackendReadiness.Ready -> emptyList()
    else -> listOf(
        SetupRemediationAction(code = code, title = title, steps = remediationSteps),
    )
}
