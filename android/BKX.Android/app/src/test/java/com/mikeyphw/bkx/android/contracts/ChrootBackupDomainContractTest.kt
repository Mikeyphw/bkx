package com.mikeyphw.bkx.android.contracts

import com.mikeyphw.bkx.android.core.backup.BackupDestinationSelection
import com.mikeyphw.bkx.android.core.backup.BackupEngineKind
import com.mikeyphw.bkx.android.core.backup.BackupPlanDraft
import com.mikeyphw.bkx.android.core.backup.BackupPlanIssue
import com.mikeyphw.bkx.android.core.backup.BackupSourceKind
import com.mikeyphw.bkx.android.core.backup.BackupSourceSelection
import com.mikeyphw.bkx.android.core.backup.ChrootBackupDomain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChrootBackupDomainContractTest {
    private val destination = BackupDestinationSelection("dest", "BKX backups", "/storage/emulated/0/BKX")

    @Test fun chrootSourceNeverCarriesFilesystemPath() {
        val source = BackupSourceSelection.chroot("androidos")
        assertEquals(BackupSourceKind.Chroot, source.kind)
        assertEquals("androidos", source.chrootName)
        assertNull(source.stagedPath)
        assertNull(source.originalUri)
    }

    @Test fun chrootIdentityCannotDivergeFromNativeName() {
        assertFailsWith<IllegalArgumentException> {
            BackupSourceSelection(
                sourceId = "chroot:other",
                label = "androidos",
                kind = BackupSourceKind.Chroot,
                chrootName = "androidos",
            )
        }
    }

    @Test fun chrootRequiresNativeEngineAndDestination() {
        val source = BackupSourceSelection.chroot("androidos")
        assertTrue(BackupPlanIssue.ChrootRequiresNativeExport in BackupPlanDraft(source = source, destination = destination).validationIssues())
        assertTrue(BackupPlanIssue.MissingDestination in BackupPlanDraft(engine = BackupEngineKind.Chrootctl, source = source).validationIssues())
        assertTrue(BackupPlanDraft(engine = BackupEngineKind.Chrootctl, source = source, destination = destination).validationIssues().isEmpty())
    }

    @Test fun nativeEngineRejectsFilesystemSource() {
        val source = BackupSourceSelection("fs", "Internal", "/storage/emulated/0")
        assertTrue(BackupPlanIssue.ChrootctlRequiresChrootSource in BackupPlanDraft(engine = BackupEngineKind.Chrootctl, source = source, destination = destination).validationIssues())
    }

    @Test fun goBuilderCannotSerializeChrootAsFilesystemBackup() {
        val draft = BackupPlanDraft(engine = BackupEngineKind.Chrootctl, source = BackupSourceSelection.chroot("androidos"), destination = destination)
        assertFailsWith<IllegalArgumentException> { com.mikeyphw.bkx.android.core.backup.BackupRequestBuilder().buildPlanRequest("cct05-test", draft) }
    }

    @Test fun domainBuildsNativeExportPlanFromTypedChroot() {
        val draft = BackupPlanDraft(engine = BackupEngineKind.Chrootctl, source = BackupSourceSelection.chroot("androidos"), destination = destination)
        val plan = ChrootBackupDomain.exportPlan(draft)
        assertEquals("androidos", plan.chrootName)
        assertEquals(destination, plan.destination)
    }
}
