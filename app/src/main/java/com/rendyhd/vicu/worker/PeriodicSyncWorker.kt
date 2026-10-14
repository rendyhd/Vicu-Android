package com.rendyhd.vicu.worker

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.remote.BaseUrlHolder
import com.rendyhd.vicu.domain.repository.PlatformRepositoryHooks
import com.rendyhd.vicu.notification.AlarmScheduler
import java.util.concurrent.TimeUnit

/**
 * The background sync that keeps the app current while it is closed: every 30 minutes, with a
 * network, it syncs with the server, then reschedules the reminder alarms and updates the
 * widgets from what arrived (so a reminder set on another device rings here too).
 */
class PeriodicSyncWorker(
    appContext: Context,
    params: WorkerParameters,
    private val syncEngine: SyncEngine,
    private val alarmScheduler: AlarmScheduler,
    private val hooks: PlatformRepositoryHooks,
    private val authManager: AuthManager,
    private val baseUrlHolder: BaseUrlHolder,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val runner = PeriodicSyncRunner(
            isSignedIn = {
                baseUrlHolder.ensureInitialized()
                !authManager.getVikunjaUrl().isNullOrBlank()
            },
            sync = syncEngine::performSync,
            rescheduleAlarms = alarmScheduler::rescheduleAll,
            updateWidgets = hooks::updateWidgets,
        )
        val outcome = runner.run()
        Log.d(TAG, "Periodic sync finished: $outcome")
        // Success even when the server could not be reached: the next period is soon, and the
        // one-time sync queued by local changes carries its own retries.
        return Result.success()
    }

    private companion object {
        const val TAG = "PeriodicSyncWorker"
    }
}

object PeriodicSyncScheduler {
    const val WORK_NAME = "periodic_sync"
    const val INTERVAL_MINUTES = 30L

    /** Keeps an existing schedule: starting the app, signing in or booting must not reset the timer. */
    val POLICY = ExistingPeriodicWorkPolicy.KEEP

    internal fun request(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<PeriodicSyncWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

    /** WorkManager persists the schedule across reboots; call this whenever an account is signed in. */
    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, POLICY, request())
    }

    /** Sign-out: a signed-out device must not keep syncing. */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
