package com.mikeyphw.bkx.android.core.jobs

import com.mikeyphw.bkx.android.core.backup.BackupDestinationSelection
import com.mikeyphw.bkx.android.core.chrootctl.ChrootctlExportPlan
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Stable Android-owned saved-job contract for unattended chroot exports. */
object ChrootctlSavedJobContract {
    const val EXPORT_OPERATION = "chrootctl.export"
    private const val IMPORT_PREFIX = "chrootctl.import"

    fun exportSpec(plan: ChrootctlExportPlan): OperationSpecV2 = OperationSpecV2(
        operation = EXPORT_OPERATION,
        parameters = JsonObject(mapOf(
            "chroot_name" to JsonPrimitive(plan.chrootName),
            "destination_id" to JsonPrimitive(plan.destination.destinationId),
            "destination_label" to JsonPrimitive(plan.destination.label),
            "destination_path" to JsonPrimitive(plan.destination.stagedDirectoryPath),
            "overwrite_existing" to JsonPrimitive(plan.overwriteExisting),
        )),
    )

    fun isChrootExport(spec: OperationSpecV2): Boolean = spec.operation == EXPORT_OPERATION
    fun isChrootImport(spec: OperationSpecV2): Boolean = spec.operation == IMPORT_PREFIX || spec.operation.startsWith("$IMPORT_PREFIX.")

    fun requireSchedulable(spec: OperationSpecV2) {
        require(!isChrootImport(spec)) { "Chroot imports are interactive-only and cannot be scheduled or run unattended." }
    }

    fun exportPlan(spec: OperationSpecV2): ChrootctlExportPlan {
        require(isChrootExport(spec)) { "Saved job is not a chrootctl export." }
        val p = spec.parameters
        fun string(key: String) = (p[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            ?: error("Chroot export saved job is missing $key.")
        return ChrootctlExportPlan(
            chrootName = string("chroot_name"),
            destination = BackupDestinationSelection(
                destinationId = string("destination_id"),
                label = string("destination_label"),
                stagedDirectoryPath = string("destination_path"),
            ),
            overwriteExisting = (p["overwrite_existing"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }
}
