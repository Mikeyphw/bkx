package com.mikeyphw.bkx.android.automation

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import com.mikeyphw.bkx.android.core.state.BkxSharedStateContract
import com.mikeyphw.bkx.android.core.jobs.SavedJobScheduleDefinition
import java.io.File
import java.util.UUID
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
        val schedules = readSchedules(appContext).filter { it.enabled && it.intervalMinutes >= MIN_INTERVAL_MINUTES }
        val component = ComponentName(appContext, BkxSavedJobScheduleService::class.java)
        val wantedIds = schedules.map { jobSchedulerId(it.scheduleId) }.toSet()
        val existing = scheduler.allPendingJobs
            .filter { it.service == component }
            .associateBy { it.id }

        existing.values
            .filter { it.id !in wantedIds }
            .forEach { scheduler.cancel(it.id) }

        var registered = 0
        schedules.forEach { schedule ->
            val signature = scheduleSignature(schedule)
            val jobId = jobSchedulerId(schedule.scheduleId)
            if (existing[jobId]?.extras?.getString(EXTRA_SIGNATURE) == signature) {
                registered++
                return@forEach
            }
            val extras = PersistableBundle().apply {
                putString(EXTRA_SCHEDULE_ID, schedule.scheduleId)
                putString(EXTRA_JOB_ID, schedule.jobId)
                putString(EXTRA_SIGNATURE, signature)
            }
            val builder = JobInfo.Builder(jobId, component)
                .setExtras(extras)
                .setPersisted(true)
                .setRequiresCharging(schedule.requireCharging)
                .setPeriodic(schedule.intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES) * 60_000L)
            when (schedule.network.lowercase()) {
                "unmetered" -> builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                "any" -> builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            }
            if (scheduler.schedule(builder.build()) == JobScheduler.RESULT_SUCCESS) registered++
        }
        return registered
    }

    fun readSchedules(context: Context): List<BkxSavedJobSchedule> {
        val shared = File(BkxSharedStateContract.ROOT, "schedules/schedules.json")
        val privateFallback = context.filesDir.resolve("bkx/go-local/home/.local/share/bkx/schedules/schedules.json")
        val source = listOf(shared, privateFallback).firstOrNull { it.isFile && it.canRead() } ?: return emptyList()
        return runCatching { json.decodeFromString<BkxScheduleDocument>(source.readText()).schedules }.getOrDefault(emptyList())
    }

    internal fun jobSchedulerId(scheduleId: String): Int = JOB_ID_PREFIX or (scheduleId.hashCode() and JOB_ID_MASK)

    internal fun scheduleSignature(schedule: BkxSavedJobSchedule): String = listOf(
        schedule.scheduleId,
        schedule.jobId,
        schedule.intervalMinutes.toString(),
        schedule.requireCharging.toString(),
        schedule.network.lowercase(),
    ).joinToString("|")

    const val EXTRA_SCHEDULE_ID = "bkx_schedule_id"
    const val EXTRA_JOB_ID = "bkx_schedule_job_id"
    const val EXTRA_SIGNATURE = "bkx_schedule_signature"
    const val MIN_INTERVAL_MINUTES = 15L
    private const val JOB_ID_PREFIX = 0x30000000
    private const val JOB_ID_MASK = 0x0fffffff
}

class BkxSavedJobScheduleService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val scheduleId = params.extras.getString(BkxSavedJobScheduler.EXTRA_SCHEDULE_ID).orEmpty()
        val jobId = params.extras.getString(BkxSavedJobScheduler.EXTRA_JOB_ID).orEmpty()
        if (scheduleId.isBlank() || jobId.isBlank()) {
            jobFinished(params, false)
            return false
        }
        val request = AutomationRequest(
            action = AutomationAction.RunSavedJob,
            requestId = "schedule-$scheduleId-${UUID.randomUUID()}",
            jobId = jobId,
        )
        AutomationDispatcher.enqueue(this, AutomationDispatcher.create(request))
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
