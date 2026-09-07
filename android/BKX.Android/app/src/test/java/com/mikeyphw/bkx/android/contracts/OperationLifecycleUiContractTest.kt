package com.mikeyphw.bkx.android.contracts

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationLifecycleUiContractTest {
    private val mainRoot = File("src/main/java/com/mikeyphw/bkx/android")

    @Test
    fun productShellRequestsNotificationPermissionAndSurfacesTransientResults() {
        val source = File(mainRoot, "feature/home/BkxProductApp.kt").readText()
        assertTrue(source.contains("ActivityResultContracts.RequestPermission"))
        assertTrue(source.contains("Manifest.permission.POST_NOTIFICATIONS"))
        assertTrue(source.contains("SnackbarHost"))
        assertTrue(source.contains("ConsumeUiMessage"))
        assertFalse(source.contains("CenterAlignedTopAppBar"))
        assertFalse(source.contains("fun BkxDestination.glyph"))
    }

    @Test
    fun userOperationFeedbackIsScopedAndBackgroundRequestsCannotOverwriteIt() {
        val source = File(mainRoot, "app/BkxAppViewModel.kt").readText()
        assertTrue(source.contains("operationFeedback: Map<BkxDestination, OperationSurfaceFeedback>"))
        assertTrue(source.contains("feedbackDestination: BkxDestination? = null"))
        assertTrue(source.contains("feedbackDestination = BkxDestination.Backup"))
        assertTrue(source.contains("feedbackDestination = BkxDestination.Archives"))
        assertTrue(source.contains("feedbackDestination = BkxDestination.More"))
        assertFalse(source.contains("surfaceResult"))
    }

    @Test
    fun backupSurfaceShowsLiveAndTerminalOperationFeedback() {
        val source = File(mainRoot, "feature/backup/BackupScreen.kt").readText()
        assertTrue(source.contains("activeBackup"))
        assertTrue(source.contains("operationFeedback[BkxDestination.Backup]"))
        assertTrue(source.contains("BACKUP COMPLETE"))
        assertTrue(source.contains("BACKUP FAILED"))
        assertTrue(source.contains("Cancel backup"))
    }

    @Test
    fun foregroundServiceSupportsAndroid16ProgressCancelAndTerminalNotifications() {
        val source = File(mainRoot, "app/service/BkxOperationForegroundService.kt").readText()
        assertTrue(source.contains("Notification.ProgressStyle"))
        assertTrue(source.contains("android.requestPromotedOngoing"))
        assertTrue(source.contains("ACTION_CANCEL_OPERATION"))
        assertTrue(source.contains("publishTerminal"))
        assertTrue(source.contains("setUsesChronometer(true)"))
    }

    @Test
    fun activityUsesLazyHistoryAndSettingsRowsUseSingleFullRowToggle() {
        val activity = File(mainRoot, "feature/activity/ActivityScreen.kt").readText()
        val components = File(mainRoot, "app/ui/components/BkxComponents.kt").readText()
        assertTrue(activity.contains("LazyColumn"))
        assertFalse(activity.contains("verticalScroll"))
        assertTrue(components.contains(".toggleable("))
        assertTrue(components.contains("Role.Switch"))
        assertTrue(components.contains("onCheckedChange = null"))
    }
}
