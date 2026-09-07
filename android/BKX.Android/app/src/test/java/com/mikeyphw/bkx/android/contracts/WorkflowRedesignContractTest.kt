package com.mikeyphw.bkx.android.contracts

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowRedesignContractTest {
    private val mainRoot = File("src/main/java/com/mikeyphw/bkx/android")

    @Test
    fun backupUsesLocationFirstWorkflowAndShowsPreflightCapacity() {
        val screen = File(mainRoot, "feature/backup/BackupScreen.kt").readText()
        val models = File(mainRoot, "core/backup/BackupModels.kt").readText()
        val viewModel = File(mainRoot, "app/BkxAppViewModel.kt").readText()
        assertTrue(screen.contains("Choose source"))
        assertTrue(screen.contains("Files & other apps"))
        assertTrue(screen.contains("Manual path · Advanced"))
        assertTrue(screen.contains("Preflight"))
        assertTrue(screen.contains("Free at destination"))
        assertTrue(screen.contains("Start backup"))
        assertTrue(models.contains("val filesTotal: Long? = null"))
        assertTrue(models.contains("val bytesTotal: Long? = null"))
        assertTrue(viewModel.contains("filesTotal = result.metrics?.filesTotal"))
        assertTrue(viewModel.contains("bytesTotal = result.metrics?.bytesTotal"))
    }

    @Test
    fun archiveLibraryUsesDedicatedAdaptiveListDetailWorkflow() {
        val screen = File(mainRoot, "feature/archives/ArchivesScreen.kt").readText()
        val state = File(mainRoot, "app/BkxAppViewModel.kt").readText()
        assertTrue(screen.contains("LazyColumn"))
        assertTrue(screen.contains("maxWidth >= 840.dp"))
        assertTrue(screen.contains("ArchiveDetailPane"))
        assertTrue(screen.contains("Back to archives"))
        assertTrue(screen.contains("Open another archive"))
        assertTrue(screen.contains("Files & other apps"))
        assertTrue(state.contains("ArchiveDetailSection.Restore"))
        assertTrue(state.contains("CloseArchiveDetails"))
        assertFalse(screen.contains("Open an archive by path"))
    }

    @Test
    fun transferIsDedicatedWorkflowAndMoreIsNavigationHub() {
        val more = File(mainRoot, "feature/more/MoreScreen.kt").readText()
        val transfer = File(mainRoot, "feature/transfer/TransferScreen.kt").readText()
        assertTrue(more.contains("OpenMoreRoute(MoreRoute.Transfer)"))
        assertTrue(more.contains("Engine diagnostics"))
        assertTrue(more.contains("Notification settings"))
        assertTrue(more.contains("Automatic"))
        assertFalse(more.contains("private fun TransferPanel"))
        assertTrue(transfer.contains("fun TransferScreen"))
        assertTrue(transfer.contains("Back to More"))
        assertTrue(transfer.contains("Files & other apps"))
        assertTrue(transfer.contains("Manual path · Advanced"))
        assertTrue(transfer.contains("Choose source"))
        assertTrue(transfer.contains("Choose destination"))
    }

    @Test
    fun homePrioritizesLastBackupAndOnlyExpandsSetupWhenNeeded() {
        val home = File(mainRoot, "feature/home/HomeScreen.kt").readText()
        assertTrue(home.contains("setupNeedsAttention"))
        assertTrue(home.contains("Last backup"))
        assertTrue(home.contains("Ready to protect your files"))
        assertFalse(home.contains("BkxSectionHeader(\"Engine\")"))
    }

    @Test
    fun bridgeCarriesBackupPlanTotalsWithoutProtocolVersionBump() {
        val bridge = File(mainRoot, "core/bridge/BridgeModels.kt").readText()
        val json = File(mainRoot, "core/bridge/BridgeJson.kt").readText()
        assertTrue(bridge.contains("@SerialName(\"files_total\") val filesTotal"))
        assertTrue(bridge.contains("@SerialName(\"bytes_total\") val bytesTotal"))
        assertTrue(json.contains("SUPPORTED_PROTOCOL_VERSION = 1"))
    }
}
