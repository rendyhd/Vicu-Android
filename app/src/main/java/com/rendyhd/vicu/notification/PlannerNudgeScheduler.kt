package com.rendyhd.vicu.notification

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.rendyhd.vicu.data.local.BehaviorPrefs
import com.rendyhd.vicu.util.PlannerNudge
import com.rendyhd.vicu.util.TimeSource
import com.rendyhd.vicu.worker.PlannerNudgeWorker
import java.util.concurrent.TimeUnit
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Plan my day's evening nudge (contract 14.10). Like the daily summaries, a one-time job for the next
 * local occurrence of the time, and the job queues the following evening's when it has run, so a
 * clock change does not move it.
 */
class PlannerNudgeScheduler(
    private val context: Context,
    private val time: TimeSource,
) {
    companion object {
        internal const val WORK_NAME = "planner_nudge"
        const val KEY_TARGET_MILLIS = "target_millis"

        /** The job that runs at [target]; built without a Context. */
        internal fun request(target: Instant, now: Instant): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<PlannerNudgeWorker>()
                .setInitialDelay((target - now).inWholeMilliseconds.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_TARGET_MILLIS to target.toEpochMilliseconds()))
                .build()
    }

    /**
     * Keeps the job in step with the settings, wherever they were changed (Settings, the gear, a
     * session's Ready step). The first value only makes sure a job exists: a queued one stays.
     */
    suspend fun follow(prefs: Flow<BehaviorPrefs>) {
        var starting = true
        prefs.map { PlannerNudge.timeOf(it.plannerEnabled, it.plannerNudge) }
            .distinctUntilChanged()
            .collect { clock ->
                when {
                    clock == null -> cancel()
                    starting -> enqueue(clock, time.now(), ExistingWorkPolicy.KEEP)
                    else -> enqueue(clock, time.now(), ExistingWorkPolicy.REPLACE)
                }
                starting = false
            }
    }

    /** After sign-in or a reboot: what [prefs] says, a queued job left as it is. */
    fun ensureScheduled(prefs: BehaviorPrefs) {
        val clock = PlannerNudge.timeOf(prefs.plannerEnabled, prefs.plannerNudge)
        if (clock == null) cancel() else enqueue(clock, time.now(), ExistingWorkPolicy.KEEP)
    }

    /** Called by the job once it has run. [after] is the later of now and the time it was due. */
    fun scheduleNext(clock: String, after: Instant) {
        enqueue(clock, after, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    private fun enqueue(clock: String, after: Instant, policy: ExistingWorkPolicy) {
        val target = PlannerNudge.nextAfter(after, time.zone(), clock)
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request(target, time.now()))
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
