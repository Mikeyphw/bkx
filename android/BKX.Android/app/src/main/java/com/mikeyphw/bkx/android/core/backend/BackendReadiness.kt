package com.mikeyphw.bkx.android.core.backend

sealed interface BackendReadiness {
    val code: String
    val title: String
    val message: String
    val remediationSteps: List<String>

    data object Checking : BackendReadiness {
        override val code = "checking"
        override val title = "Checking BKX engine"
        override val message = "BKX engine readiness has not finished yet."
        override val remediationSteps = emptyList<String>()
    }

    data class Ready(
        val backendVersion: String,
        val bridgeProtocolVersion: Int,
        val capabilities: Set<String>,
        val runtime: String = "embedded-cpython",
    ) : BackendReadiness {
        val isRoot: Boolean get() = "root.filesystem" in capabilities

        override val code = "ready"
        override val title = if (isRoot) "BKX root engine ready" else "BKX engine ready"
        override val message = if (isRoot) {
            "BKX $backendVersion is embedded in this app and running with root filesystem access."
        } else {
            "BKX $backendVersion is embedded in this app. Root-only paths remain unavailable until root is granted."
        }
        override val remediationSteps = emptyList<String>()
    }

    data object RootPermissionRequired : BackendReadiness {
        override val code = "root_permission_required"
        override val title = "Root access required"
        override val message = "BKX is configured to require root, but the device root manager has not granted it."
        override val remediationSteps = listOf(
            "Grant BKX root access in your root manager.",
            "Run the engine check again.",
        )
    }

    data class UnsupportedProtocol(
        val expected: Int,
        val actual: Int,
    ) : BackendReadiness {
        override val code = "unsupported_protocol"
        override val title = "Unsupported engine protocol"
        override val message = "The app supports protocol $expected, but the embedded engine reported $actual."
        override val remediationSteps = listOf("Update the Android app and embedded BKX engine together.")
    }

    data class ProbeFailed(
        val reason: String,
    ) : BackendReadiness {
        override val code = "probe_failed"
        override val title = "BKX engine check failed"
        override val message = reason
        override val remediationSteps = listOf("Review engine diagnostics, then retry the check.")
    }
}
