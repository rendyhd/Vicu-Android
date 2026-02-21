package com.rendyhd.vicu.widget

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

object WidgetUpdateScheduler {

    private const val PERIODIC_WORK_NAME = "widget_periodic_refresh"

    fun schedulePeriodicRefresh(context: Context) {
        val request = PeriodicWorkRequestBuilder<TaskWidgetWorker>(15, TimeUnit.MINUTES)
            .setInputData(workDataOf("update_all" to true))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun enqueueImmediateUpdate(context: Context, appWidgetId: Int) {
        val request = OneTimeWorkRequestBuilder<TaskWidgetWorker>()
            .setInputData(workDataOf("app_widget_id" to appWidgetId))
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    fun enqueueImmediateUpdateAll(context: Context) {
        val request = OneTimeWorkRequestBuilder<TaskWidgetWorker>()
            .setInputData(workDataOf("update_all" to true))
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }
}
