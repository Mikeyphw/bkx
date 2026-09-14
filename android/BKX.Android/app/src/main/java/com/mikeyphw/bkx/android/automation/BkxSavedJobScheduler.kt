package com.mikeyphw.bkx.android.automation

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import androidx.core.content.edit
import com.mikeyphw.bkx.android.app.di.DefaultBackendGraph
import com.mikeyphw.bkx.android.app.runtime.BkxStartupIngress
import com.mikeyphw.bkx.android.core.jobs.SAVED_JOB_SCHEMA_VERSION
import com.mikeyphw.bkx.android.core.jobs.SavedJob
import com.mikeyphw.bkx.android.core.jobs.SavedJobScheduleDefinition
import com.mikeyphw.bkx.android.core.jobs.SavedJobStoreDocument
import com.mikeyphw.bkx.android.core.state.BkxControlPlaneRootResolver
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
private data class BkxScheduleDocument(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val schedules: List<BkxSavedJobSchedule> = emptyList(),
)

typealias BkxSavedJobSchedule = SavedJobScheduleDefinition


object BkxSavedJobScheduler {
    private val json = Json { ignoreUnknownKeys = true }

    fun reconcile(context: Context): Int {
        val appContext = context.applicationContext ?: context
        val scheduler = appContext.getSystemService(JobScheduler::class.java) ?: return 0
        val jobsById = readJobs(appContext).associateBy { it.jobId }
        val schedules = readSchedules(appContext).filter { schedule ->
            val job = jobsById[schedule.jobId]
            schedule.enabled && schedule.intervalMinutes in MIN_INTERVAL_MINUTES..MAX_INTERVAL_MINUTES && job?.enabled == true
        }
        val idStore = ScheduleJobIdStore(appContext)
        val assignedIds = idStore.assignIds(schedules.map { it.scheduleId })
        val component = ComponentName(appContext, BkxSavedJobScheduleService::class.java)
        val wantedIds = schedules.mapNotNull { assignedIds[it.scheduleId] }.toSet()
        val existing = scheduler.allPendingJobs
            .filter { it.service == component }
            .associateBy { it.id }

        existing.values
            .filter { it.id !in wantedIds }
            .forEach { scheduler.cancel(it.id) }

        var registered = 0
        schedules.forEach { schedule ->
            val job = jobsById[schedule.jobId] ?: return@forEach
            val signature = scheduleSignature(schedule, job.revision)
            val schedulerId = assignedIds[schedule.scheduleId] ?: return@forEach
            if (existing[schedulerId]?.extras?.getString(EXTRA_SIGNATURE) == signature) {
                registered++
                return@forEach
            }
            val extras = PersistableBundle().apply {
                putString(EXTRA_SCHEDULE_ID, schedule.scheduleId)
                putString(EXTRA_JOB_ID, schedule.jobId)
                putString(EXTRA_SIGNATURE, signature)
                putLong(EXTRA_JOB_REVISION, job.revision)
            }
            val builder = JobInfo.Builder(schedulerId, component)
                .setExtras(extras)
                .setPersisted(true)
                .setRequiresCharging(schedule.requireCharging)
                .setPeriodic(schedule.intervalMinutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES) * 60_000L)
            when (schedule.network.lowercase()) {
                "unmetered" -> builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                "any" -> builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            }
            if (scheduler.schedule(builder.build()) == JobScheduler.RESULT_SUCCESS) registered++
        }
        return registered
    }

    fun readSchedules(context: Context): List<BkxSavedJobSchedule> {
        val source = BkxControlPlaneRootResolver.resolveDocument(context, "schedules/schedules.json") ?: return emptyList()
        val encoded = BkxControlPlaneRootResolver.readBoundedDocument(source) ?: return emptyList()
        return runCatching {
            val document = json.decodeFromString<BkxScheduleDocument>(encoded)
            require(document.schemaVersion == 1) { "Unsupported BKX schedule schema." }
            document.schedules.take(BkxControlPlaneRootResolver.MAX_CONTROL_PLANE_ITEMS)
        }.getOrDefault(emptyList())
    }

    fun readJobs(context: Context): List<SavedJob> {
        val source = BkxControlPlaneRootResolver.resolveDocument(context, "jobs/jobs.json") ?: return emptyList()
        val encoded = BkxControlPlaneRootResolver.readBoundedDocument(source) ?: return emptyList()
        return runCatching {
            val document = json.decodeFromString<SavedJobStoreDocument>(encoded)
            require(document.schemaVersion == SAVED_JOB_SCHEMA_VERSION) { "Unsupported BKX jobs schema." }
            document.jobs.take(BkxControlPlaneRootResolver.MAX_CONTROL_PLANE_ITEMS)
        }.getOrDefault(emptyList())
    }

    @Deprecated("Use ScheduleJobIdStore.assignIds so valid schedule ids cannot collide inside JobScheduler.")
    internal fun jobSchedulerId(scheduleId: String): Int = JOB_ID_PREFIX or (scheduleId.hashCode() and JOB_ID_MASK)

    internal fun scheduleSignature(schedule: BkxSavedJobSchedule, jobRevision: Long = 0L): String = listOf(
        schedule.scheduleId,
        schedule.jobId,
        jobRevision.toString(),
        schedule.intervalMinutes.toString(),
        schedule.requireCharging.toString(),
        schedule.network.lowercase(),
    ).joinToString("|")

    const val EXTRA_SCHEDULE_ID = "bkx_schedule_id"
    const val EXTRA_JOB_ID = "bkx_schedule_job_id"
    const val EXTRA_SIGNATURE = "bkx_schedule_signature"
    const val EXTRA_JOB_REVISION = "bkx_schedule_job_revision"
    const val MIN_INTERVAL_MINUTES = 15L
    const val MAX_INTERVAL_MINUTES = 525_600L
    private const val JOB_ID_PREFIX = 0x30000000
    private const val JOB_ID_MASK = 0x0fffffff
}

private class ScheduleJobIdStore(context: Context) {
    private val preferences = (context.applicationContext ?: context).getSharedPreferences("bkx_schedule_job_ids", Context.MODE_PRIVATE)

    fun assignIds(scheduleIds: List<String>): Map<String, Int> {
        val wanted = scheduleIds.distinct().sorted()
        val assigned = linkedMapOf<String, Int>()
        val used = mutableSetOf<Int>()
        wanted.forEach { scheduleId ->
            val current = preferences.getInt("id:$scheduleId", 0)
            if (current in VALID_JOB_ID_RANGE && used.add(current)) {
                assigned[scheduleId] = current
            }
        }
        var slot = 1
        wanted.forEach { scheduleId ->
            if (scheduleId in assigned) return@forEach
            while ((JOB_ID_PREFIX or slot) in used) slot++
            val id = JOB_ID_PREFIX or slot
            assigned[scheduleId] = id
            used.add(id)
            slot++
        }
        preferences.edit(commit = true) {
            putString(KEY_INDEX, wanted.joinToString("\n"))
            wanted.forEach { putInt("id:$it", assigned.getValue(it)) }
            preferences.getString(KEY_INDEX, "").orEmpty().lineSequence()
                .filter(String::isNotBlank)
                .filterNot { it in wanted }
                .forEach { remove("id:$it") }
        }
        return assigned
    }

    private companion object {
        const val KEY_INDEX = "schedule_ids"
        const val JOB_ID_PREFIX = 0x30000000
        val VALID_JOB_ID_RANGE = JOB_ID_PREFIX..(JOB_ID_PREFIX or 0x0fffffff)
    }
}

class BkxSavedJobScheduleService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters): Boolean {
        val scheduleId = params.extras.getString(BkxSavedJobScheduler.EXTRA_SCHEDULE_ID).orEmpty()
        val jobId = params.extras.getString(BkxSavedJobScheduler.EXTRA_JOB_ID).orEmpty()
        val signature = params.extras.getString(BkxSavedJobScheduler.EXTRA_SIGNATURE).orEmpty()
        val jobRevision = params.extras.getLong(BkxSavedJobScheduler.EXTRA_JOB_REVISION, 0L)
        if (scheduleId.isBlank() || jobId.isBlank()) {
            jobFinished(params, false)
            return false
        }
        scope.launch {
            try {
                DefaultBackendGraph.applicationRuntime(this@BkxSavedJobScheduleService)
                    .awaitReadyForExecution(BkxStartupIngress.ScheduledJob)
                val currentSchedule = BkxSavedJobScheduler.readSchedules(this@BkxSavedJobScheduleService)
                    .firstOrNull { it.scheduleId == scheduleId && it.jobId == jobId }
                val currentJob = BkxSavedJobScheduler.readJobs(this@BkxSavedJobScheduleService).firstOrNull { it.jobId == jobId }
                if (currentSchedule == null || currentJob == null || !currentSchedule.enabled || !currentJob.enabled ||
                    BkxSavedJobScheduler.scheduleSignature(currentSchedule, currentJob.revision) != signature
                ) {
                    BkxSavedJobScheduler.reconcile(this@BkxSavedJobScheduleService)
                    return@launch
                }
                val request = AutomationRequest(
                    action = AutomationAction.RunSavedJob,
                    requestId = "schedule-$scheduleId-${UUID.randomUUID()}",
                    jobId = jobId,
                    scheduleId = scheduleId,
                    scheduleSignature = signature,
                    expectedJobRevision = jobRevision,
                )
                DefaultBackendGraph.automationEngine(this@BkxSavedJobScheduleService).execute(
                    request,
                    AutomationProgressSink.None,
                )
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        scope.coroutineContext.cancelChildren()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
