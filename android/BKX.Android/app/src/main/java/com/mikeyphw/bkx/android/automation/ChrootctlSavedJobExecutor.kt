package com.mikeyphw.bkx.android.automation

import com.mikeyphw.bkx.android.app.chrootctl.ChrootctlExportCoordinator
import com.mikeyphw.bkx.android.core.chrootctl.ChrootctlBridgePort
import com.mikeyphw.bkx.android.core.chrootctl.ChrootctlReadinessPort
import com.mikeyphw.bkx.android.core.jobs.ChrootctlSavedJobContract
import com.mikeyphw.bkx.android.core.jobs.SavedJob
import java.util.UUID

interface ChrootctlSavedJobExecutionPort {
    suspend fun execute(job: SavedJob, request: AutomationRequest, progress: AutomationProgressSink): AutomationResult
}

class ChrootctlSavedJobExecutor(
    private val readiness: ChrootctlReadinessPort,
    private val bridge: ChrootctlBridgePort,
    private val coordinator: ChrootctlExportCoordinator,
    private val driveRefresh: ManagedDriveRefreshPort,
) : ChrootctlSavedJobExecutionPort {
    override suspend fun execute(job: SavedJob, request: AutomationRequest, progress: AutomationProgressSink): AutomationResult {
        if (ChrootctlSavedJobContract.isChrootImport(job.operation)) {
            return failure(request, job, "chroot_import_interactive_only", "Chroot imports cannot run from Saved Jobs, schedules, Tasker, or other unattended entry points.")
        }
        val plan = runCatching { ChrootctlSavedJobContract.exportPlan(job.operation) }.getOrElse {
            return failure(request, job, "chroot_export_job_invalid", it.message ?: "Invalid chroot export job.")
        }
        progress.onStage("Checking root and chrootctl")
        val ready = readiness.probe()
        if (!ready.ready) return failure(request, job, "chrootctl_not_ready", ready.detail)

        val installed = runCatching { bridge.listChroots("job-chroots-${UUID.randomUUID()}") }.getOrElse {
            return failure(request, job, "chroot_inventory_failed", it.message ?: "Could not query chrootctl chroots.")
        }.any { it.installed && it.name == plan.chrootName }
        if (!installed) return failure(request, job, "chroot_not_installed", "The configured chroot '${plan.chrootName}' is not currently installed.")

        driveRefresh.refreshIfNeeded(listOf(plan.destination.stagedDirectoryPath))?.let {
            return failure(request, job, "managed_drive_refresh_failed", it)
        }
        progress.onStage("Previewing chroot export")
        val preview = runCatching { coordinator.preview(plan) }.getOrElse {
            return failure(request, job, "chroot_export_preview_failed", it.message ?: "Chroot export preview failed.")
        }
        if (!preview.isExecutable) return failure(request, job, "chroot_export_blocked", preview.blockers.joinToString("; ") { it.message })

        progress.onStage("Exporting chroot")
        val publication = runCatching {
            coordinator.exportAndPublish(plan, preview) { event -> progress.onStage(event.message) }
        }.getOrElse {
            return failure(request, job, "chroot_export_failed", it.message ?: "Chroot export failed.")
        }
        return AutomationResult(
            requestId = request.requestId,
            action = request.action,
            status = "success",
            summary = "Chroot '${publication.chrootName}' exported and published.",
            artifactPaths = listOf(publication.destinationLocation),
            artifactSha256 = listOf(publication.checksumSha256),
            jobId = job.jobId,
        )
    }

    private fun failure(request: AutomationRequest, job: SavedJob, code: String, message: String) = AutomationResult(
        requestId = request.requestId, action = request.action, status = "failure", summary = message,
        errorCode = code, errorMessage = message, jobId = job.jobId,
    )
}
