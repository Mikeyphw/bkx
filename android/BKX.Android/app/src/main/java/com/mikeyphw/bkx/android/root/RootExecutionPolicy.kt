package com.mikeyphw.bkx.android.root

import com.mikeyphw.bkx.android.core.files.AndroidPathPolicy
import com.mikeyphw.bkx.android.core.settings.RootAccessMode

enum class RootExecutionRoute {
    Root,
    Local,
    Blocked,
}

data class RootExecutionRequest(
    val operation: String,
    val candidatePaths: List<String> = emptyList(),
) {
    val rootSensitivePaths: List<String> = candidatePaths.filter { AndroidPathPolicy.rootSensitiveReason(it) != null }
}

object RootExecutionPolicy {
    fun select(mode: RootAccessMode, rootAvailable: Boolean): RootExecutionRoute = when (mode) {
        RootAccessMode.Preferred -> if (rootAvailable) RootExecutionRoute.Root else RootExecutionRoute.Local
        RootAccessMode.Required -> if (rootAvailable) RootExecutionRoute.Root else RootExecutionRoute.Blocked
        RootAccessMode.Disabled -> RootExecutionRoute.Local
    }

    fun select(mode: RootAccessMode, rootAvailable: Boolean, request: RootExecutionRequest): RootExecutionRoute {
        val rootSensitive = request.rootSensitivePaths.isNotEmpty()
        return when (mode) {
            RootAccessMode.Disabled -> if (rootSensitive) RootExecutionRoute.Blocked else RootExecutionRoute.Local
            RootAccessMode.Required -> if (rootAvailable) RootExecutionRoute.Root else RootExecutionRoute.Blocked
            RootAccessMode.Preferred -> when {
                rootSensitive && rootAvailable -> RootExecutionRoute.Root
                rootSensitive -> RootExecutionRoute.Blocked
                else -> RootExecutionRoute.Local
            }
        }
    }
}
