package com.mikeyphw.bkx.android.core.backup

import com.mikeyphw.bkx.android.core.chrootctl.ChrootctlChrootRecord
import com.mikeyphw.bkx.android.core.chrootctl.ChrootctlExportPlan
import com.mikeyphw.bkx.android.core.chrootctl.ChrootctlExportPreview

/** Domain adapter that keeps chroots distinct from filesystem paths. */
object ChrootBackupDomain {
    fun source(record: ChrootctlChrootRecord): BackupSourceSelection =
        BackupSourceSelection.chroot(record.name, record.displayLabel())

    fun exportPlan(draft: BackupPlanDraft): ChrootctlExportPlan {
        draft.requireValid()
        require(draft.engine == BackupEngineKind.Chrootctl) { "Chroot export requires the chrootctl engine." }
        val source = requireNotNull(draft.source)
        require(source.kind == BackupSourceKind.Chroot) { "Chroot export requires a typed chroot source." }
        return ChrootctlExportPlan(
            chrootName = requireNotNull(source.chrootName),
            destination = requireNotNull(draft.destination),
        )
    }

    fun preview(draft: BackupPlanDraft, native: ChrootctlExportPreview): BackupPlanPreview {
        require(native.planFingerprint == exportPlan(draft).fingerprint()) { "Native chroot preview no longer matches the backup draft." }
        val warnings = buildList {
            native.blockers.forEach { add(BackupPlanWarning(it.code, it.message, BackupWarningSeverity.Blocker)) }
            native.warnings.forEach { add(BackupPlanWarning("chrootctl", it, BackupWarningSeverity.Warning)) }
        }
        return BackupPlanPreview(
            requestId = native.requestId,
            planToken = native.contract.previewId,
            draftFingerprint = draft.fingerprint(),
            bytesTotal = native.sourceSizeBytes,
            warnings = warnings,
            verificationExpectations = listOf(
                BackupVerificationExpectation("sha256", "Verify the native chroot export before BKX publishes it."),
            ),
        )
    }
}

private fun ChrootctlChrootRecord.displayLabel(): String = buildString {
    append(name)
    distro?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
    releaseName.takeIf { it.isNotBlank() }?.let { append(" ").append(it) }
}
