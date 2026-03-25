package com.rendyhd.vicu.worker

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object TokenRefreshScheduler {

    private const val TAG = "TokenRefreshScheduler"
    private const val WORK_NAME = "token_refresh_periodic"

    /**
     * Schedule periodic token refresh every 6 hours.
     * This keeps the Vikunja refresh token alive even when the app process is dead,
     * preventing logout when the user doesn't open the app for several days.
     * Uses KEEP policy so scheduling from every app launch doesn't reset the timer.
     */
    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<TokenRefreshWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
        Log.d(TAG, "Periodic token refresh scheduled (every 6 hours)")
    }

    /**
     * Cancel periodic refresh (called on logout).
     */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        Log.d(TAG, "Periodic token refresh cancelled")
    }
}
