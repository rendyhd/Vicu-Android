package com.rendyhd.vicu.worker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rendyhd.vicu.MainActivity
import com.rendyhd.vicu.R
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.remote.BaseUrlHolder
import com.rendyhd.vicu.notification.NotificationChannelManager
import com.rendyhd.vicu.notification.PlannerNudgeScheduler
import com.rendyhd.vicu.putViewTarget
import com.rendyhd.vicu.ui.navigation.ViewTarget
import com.rendyhd.vicu.util.PlannerNudge
import com.rendyhd.vicu.util.TimeSource
import kotlinx.coroutines.flow.first
import kotlin.time.Instant

/**
 * One evening's nudge to plan tomorrow: a notification that opens Plan, then the next evening's job.
 * The settings are read again here: the time or the planner may have changed since it was queued.
 */
class PlannerNudgeWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val behaviorPrefsStore: BehaviorPrefsStore,
    private val scheduler: PlannerNudgeScheduler,
    private val authManager: AuthManager,
    private val baseUrlHolder: BaseUrlHolder,
    private val time: TimeSource,
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "PlannerNudgeWorker"
        private const val NOTIFICATION_ID = 999_997
    }

    override suspend fun doWork(): Result {
        baseUrlHolder.ensureInitialized()
        // Nobody signed in: nothing is shown, and signing in schedules it again.
        if (authManager.getVikunjaUrl().isNullOrBlank()) return Result.success()

        val prefs = behaviorPrefsStore.getPrefs().first()
        val clock = PlannerNudge.timeOf(prefs.plannerEnabled, prefs.plannerNudge) ?: return Result.success()
        post()

        val target = inputData.getLong(PlannerNudgeScheduler.KEY_TARGET_MILLIS, 0L)
        scheduler.scheduleNext(clock, maxOf(time.now(), Instant.fromEpochMilliseconds(target)))
        // Never retry: a late retry would nudge twice.
        return Result.success()
    }

    private fun post() {
        val open = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putViewTarget(ViewTarget.Plan)
        }
        val pending = PendingIntent.getActivity(
            applicationContext,
            NOTIFICATION_ID,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, NotificationChannelManager.CHANNEL_PLANNER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(PlannerNudge.TITLE)
            .setContentText(PlannerNudge.BODY)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try {
            NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing POST_NOTIFICATIONS permission", e)
        }
    }
}
