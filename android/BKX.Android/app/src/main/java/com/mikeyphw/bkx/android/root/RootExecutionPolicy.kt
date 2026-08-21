package com.mikeyphw.bkx.android.root

import com.mikeyphw.bkx.android.core.settings.RootAccessMode

enum class RootExecutionRoute {
    Root,
    Local,
    Blocked,
}

object RootExecutionPolicy {
    fun select(mode: RootAccessMode, rootAvailable: Boolean): RootExecutionRoute = when (mode) {
        RootAccessMode.Preferred -> if (rootAvailable) RootExecutionRoute.Root else RootExecutionRoute.Local
        RootAccessMode.Required -> if (rootAvailable) RootExecutionRoute.Root else RootExecutionRoute.Blocked
        RootAccessMode.Disabled -> RootExecutionRoute.Local
    }
}
