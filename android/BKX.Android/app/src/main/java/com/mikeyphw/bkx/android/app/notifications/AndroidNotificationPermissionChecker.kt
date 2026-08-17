
package com.mikeyphw.bkx.android.app.notifications

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.mikeyphw.bkx.android.core.notifications.BkxNotificationPermissionStatus

object AndroidNotificationPermissionChecker {
    fun status(context: Context): BkxNotificationPermissionStatus = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> BkxNotificationPermissionStatus.NotRequired
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ->
            BkxNotificationPermissionStatus.Granted
        else -> BkxNotificationPermissionStatus.Denied
    }
}
