package com.mikeyphw.bkx.android.core.backend

sealed interface BackendReadiness {
    val code: String
    val title: String
    val message: String
    val remediationSteps: List<String>

    data object Checking : BackendReadiness {
        override val code = "checking"
        override val title = "Checking backend"
        override val message = "BKX backend readiness has not finished yet."
        override val remediationSteps = emptyList<String>()
    }

    data class Ready(
        val backendVersion: String,
        val bridgeProtocolVersion: Int,
        val capabilities: Set<String>,
    ) : BackendReadiness {
        override val code = "ready"
        override val title = "BKX backend ready"
        override val message = "BKX $backendVersion is available through bridge protocol $bridgeProtocolVersion."
        override val remediationSteps = emptyList<String>()
    }

    data object TermuxNotVisibleOrMissing : BackendReadiness {
        override val code = "termux_missing"
        override val title = "Termux is not visible"
        override val message = "The app cannot discover the Termux package required for BKX execution."
        override val remediationSteps = listOf(
            "Install Termux from a trusted source.",
            "Keep the manifest package visibility query for com.termux enabled.",
        )
    }

    data object RunCommandPermissionMissing : BackendReadiness {
        override val code = "run_command_permission_missing"
        override val title = "RUN_COMMAND permission not granted"
        override val message = "Android has not granted permission to run the fixed BKX wrapper in Termux."
        override val remediationSteps = listOf(
            "Open Android Settings for BKX Companion.",
            "Grant the additional permission named Run commands in Termux environment.",
        )
    }

    data object AllowExternalAppsNotVerified : BackendReadiness {
        override val code = "allow_external_apps_not_verified"
        override val title = "Termux external app access needs verification"
        override val message = "Termux must allow external apps before it will execute the fixed BKX wrapper."
        override val remediationSteps = listOf(
            "Set allow-external-apps=true in ~/.termux/termux.properties.",
            "Restart Termux after changing termux.properties.",
            "Run backend check again so the wrapper can prove readiness.",
        )
    }

    data class WrapperUnavailable(
        val wrapperPath: String,
    ) : BackendReadiness {
        override val code = "wrapper_unavailable"
        override val title = "BKX wrapper unavailable"
        override val message = "The approved BKX Android wrapper was not found at $wrapperPath."
        override val remediationSteps = listOf(
            "Install the repaired BKX engine inside Termux.",
            "Install the approved bkx-android-bridge wrapper at the documented path.",
        )
    }

    data class UnsupportedProtocol(
        val expected: Int,
        val actual: Int,
    ) : BackendReadiness {
        override val code = "unsupported_protocol"
        override val title = "Unsupported bridge protocol"
        override val message = "The app supports protocol $expected, but the backend reported $actual."
        override val remediationSteps = listOf("Update BKX Companion and the Termux wrapper together.")
    }

    data class ProbeFailed(
        val reason: String,
    ) : BackendReadiness {
        override val code = "probe_failed"
        override val title = "Backend probe failed"
        override val message = reason
        override val remediationSteps = listOf("Review Termux and BKX setup, then retry backend check.")
    }
}
