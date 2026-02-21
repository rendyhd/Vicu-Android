package com.rendyhd.vicu.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object SyncScheduler {

    private const val WORK_NAME_ONLINE = "sync_when_online"
    private const val WORK_NAME_IMMEDIATE = "sync_immediate"

    fun enqueueWhenOnline(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME_ONLINE,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun enqueueImmediate(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME_IMMEDIATE,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
