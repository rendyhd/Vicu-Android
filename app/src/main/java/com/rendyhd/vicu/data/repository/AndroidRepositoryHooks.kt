package com.rendyhd.vicu.data.repository

import android.content.Context
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.PlatformRepositoryHooks
import com.rendyhd.vicu.notification.AlarmScheduler
import com.rendyhd.vicu.notification.DailySummaryScheduler
import com.rendyhd.vicu.notification.RoutineAlarmScheduler
import com.rendyhd.vicu.util.CompletionSoundPlayer
import com.rendyhd.vicu.widget.WidgetUpdateScheduler
import com.rendyhd.vicu.widget.RoutineWidget
import com.rendyhd.vicu.widget.WidgetConfigStore
import com.rendyhd.vicu.worker.RoutineMaintenanceScheduler
import com.rendyhd.vicu.worker.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AndroidRepositoryHooks(
    private val context: Context,
    private val alarmScheduler: AlarmScheduler,
    private val routineAlarmSchedulerProvider: () -> RoutineAlarmScheduler,
    private val completionSoundPlayer: CompletionSoundPlayer,
    private val appScope: CoroutineScope,
    private val dailySummaryScheduler: DailySummaryScheduler,
) : PlatformRepositoryHooks {

    override fun triggerSync() {
        SyncScheduler.enqueueWhenOnline(context)
    }

    override fun updateWidgets() {
        WidgetUpdateScheduler.enqueueImmediateUpdateAll(context)
    }

    override fun playCompletionSound() {
        appScope.launch(Dispatchers.IO) {
            completionSoundPlayer.play()
        }
    }

    override suspend fun scheduleAlarm(task: Task) {
        alarmScheduler.scheduleForTask(task)
    }

    override suspend fun cancelAlarm(taskId: Long) {
        alarmScheduler.cancelForTask(taskId)
    }

    override suspend fun rescheduleAlarms() {
        alarmScheduler.rescheduleAll()
        routineAlarmSchedulerProvider().rescheduleAll()
    }

    override suspend fun updateAlarms(changed: List<Task>, removedTaskIds: Set<Long>) {
        alarmScheduler.updateAlarms(changed, removedTaskIds)
    }

    override suspend fun cancelAllAlarms() {
        alarmScheduler.cancelAll()
        routineAlarmSchedulerProvider().cancelAll()
    }

    override suspend fun cancelAccountBackgroundWork() {
        dailySummaryScheduler.cancel(DailySummaryScheduler.SLOT_MORNING)
        dailySummaryScheduler.cancel(DailySummaryScheduler.SLOT_AFTERNOON)
        RoutineMaintenanceScheduler.cancel(context)
    }

    override suspend fun clearWidgetConfigurations() {
        WidgetConfigStore.clearAll(context)
    }

    override suspend fun routinesChanged() {
        routineAlarmSchedulerProvider().rescheduleAll()
        RoutineWidget().updateAllWidgets(context)
        updateWidgets()
    }
}
