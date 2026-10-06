package com.rendyhd.vicu.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rendyhd.vicu.domain.repository.RoutineRepository
import com.rendyhd.vicu.notification.RoutineAlarmScheduler
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.widget.RoutineWidget
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class RoutineMaintenanceWorker(
    appContext: Context,
    params: WorkerParameters,
    private val repository: RoutineRepository,
    private val alarmScheduler: RoutineAlarmScheduler,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        try {
            when (val result = repository.finalizeAndPrune()) {
                is NetworkResult.Error -> Log.w(TAG, "Routine cleanup failed: ${result.message}")
                else -> Unit
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Routine cleanup failed", error)
        }

        // These are local operations and must still run when server cleanup cannot complete.
        // Otherwise the widget can remain on yesterday until the user opens the app.
        try {
            alarmScheduler.rescheduleAll()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Routine alarm refresh failed", error)
        }
        try {
            RoutineWidget().updateAllWidgets(applicationContext)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Routine widget refresh failed", error)
        }

        return try {
            RoutineMaintenanceScheduler.scheduleNext(applicationContext)
            Result.success()
        } catch (error: Exception) {
            Log.e(TAG, "Could not schedule the next midnight maintenance", error)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "RoutineMaintenance"
    }
}

object RoutineMaintenanceScheduler {
    private const val LEGACY_PERIODIC_WORK_NAME = "routine_daily_maintenance"
    private const val MIDNIGHT_WORK_NAME = "routine_midnight_maintenance_v2"
    private const val MIDNIGHT_SETTLE_MILLIS = 5_000L

    fun schedule(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(LEGACY_PERIODIC_WORK_NAME)
        manager.enqueueUniqueWork(
            MIDNIGHT_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            nextMidnightRequest(),
        )
    }

    fun reschedule(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(LEGACY_PERIODIC_WORK_NAME)
        manager.enqueueUniqueWork(
            MIDNIGHT_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            nextMidnightRequest(),
        )
    }

    /** Stops the midnight maintenance (sign-out); signing in again calls [schedule]. */
    fun cancel(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(LEGACY_PERIODIC_WORK_NAME)
        manager.cancelUniqueWork(MIDNIGHT_WORK_NAME)
    }

    internal fun scheduleNext(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            MIDNIGHT_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            nextMidnightRequest(),
        )
    }

    private fun nextMidnightRequest() =
        OneTimeWorkRequestBuilder<RoutineMaintenanceWorker>()
            .setInitialDelay(
                DateUtils.millisUntilNextMidnight() + MIDNIGHT_SETTLE_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .build()
}
