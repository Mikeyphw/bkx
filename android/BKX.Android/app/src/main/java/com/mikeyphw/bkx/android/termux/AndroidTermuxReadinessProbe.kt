package com.mikeyphw.bkx.android.termux

import android.content.Context
import android.content.pm.PackageManager
import com.mikeyphw.bkx.android.core.backend.BackendReadiness

class AndroidTermuxReadinessProbe(
    private val context: Context,
) : TermuxReadinessProbe {
    override suspend fun checkReadiness(): BackendReadiness {
        if (!isTermuxVisible()) {
            return BackendReadiness.TermuxNotVisibleOrMissing
        }
        if (context.checkSelfPermission(TermuxRunCommandContract.PERMISSION_RUN_COMMAND) != PackageManager.PERMISSION_GRANTED) {
            return BackendReadiness.RunCommandPermissionMissing
        }
        return BackendReadiness.AllowExternalAppsNotVerified
    }

    private fun isTermuxVisible(): Boolean = try {
        context.packageManager.getPackageInfo(TermuxRunCommandContract.TERMUX_PACKAGE_NAME, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}

interface TermuxReadinessProbe {
    suspend fun checkReadiness(): BackendReadiness
}
