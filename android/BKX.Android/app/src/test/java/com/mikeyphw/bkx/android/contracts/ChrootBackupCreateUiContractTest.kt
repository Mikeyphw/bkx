package com.mikeyphw.bkx.android.contracts

import kotlin.test.Test
import kotlin.test.assertTrue

class ChrootBackupCreateUiContractTest {
    @Test fun chroot_create_flow_contract_is_explicit() {
        val source = java.io.File("src/main/java/com/mikeyphw/bkx/android/feature/backup/BackupScreen.kt").readText()
        assertTrue(source.contains("Detect chrootctl & chroots"))
        assertTrue(source.contains("CHROOTCTL NATIVE EXPORT"))
        assertTrue(source.contains("SelectChrootBackupSource"))
        assertTrue(source.contains("Managed by chrootctl"))
    }

    @Test fun view_model_uses_typed_native_pipeline() {
        val source = java.io.File("src/main/java/com/mikeyphw/bkx/android/app/BkxAppViewModel.kt").readText()
        assertTrue(source.contains("bridge.listChroots"))
        assertTrue(source.contains("it.name == name && it.installed"))
        assertTrue(source.contains("event.draft.source?.kind == BackupSourceKind.Filesystem && event.draft.engine == BackupEngineKind.Chrootctl"))
        assertTrue(source.contains("ChrootBackupDomain.exportPlan"))
        assertTrue(source.contains("coordinator.preview"))
        assertTrue(source.contains("coordinator.exportAndPublish"))
        assertTrue(!source.contains("su -c chrootctl"))
    }
}
