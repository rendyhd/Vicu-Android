package com.rendyhd.vicu.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rendyhd.vicu.data.local.NotificationPrefsStore
import com.rendyhd.vicu.widget.WidgetUpdateScheduler
import com.rendyhd.vicu.widget.RoutineWidget
import com.rendyhd.vicu.worker.PeriodicSyncScheduler
import com.rendyhd.vicu.worker.RoutineMaintenanceScheduler
import com.rendyhd.vicu.worker.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class BootReceiver : BroadcastReceiver(), KoinComponent {

    companion object {
        private const val TAG = "BootReceiver"
    }

    private val alarmScheduler: AlarmScheduler by inject()
    private val dailySummaryScheduler: DailySummaryScheduler by inject()
    private val routineAlarmScheduler: RoutineAlarmScheduler by inject()
    private val prefsStore: NotificationPrefsStore by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.d(TAG, "Boot completed — rescheduling alarms")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                alarmScheduler.rescheduleAll()
                alarmScheduler.rescheduleSnoozes()
                routineAlarmScheduler.rescheduleAll()
                RoutineMaintenanceScheduler.reschedule(context)
                RoutineWidget().updateAllWidgets(context)
                val prefs = prefsStore.getPrefs().first()
                dailySummaryScheduler.scheduleIfEnabled(
                    prefs.dailySummaryEnabled,
                    prefs.dailySummaryHour,
                    prefs.dailySummaryMinute,
                )
                dailySummaryScheduler.scheduleIfEnabled(
                    DailySummaryScheduler.SLOT_AFTERNOON,
                    prefs.afternoonSummaryEnabled,
                    prefs.afternoonSummaryHour,
                    prefs.afternoonSummaryMinute,
                )
                WidgetUpdateScheduler.enqueueImmediateUpdateAll(context)
                SyncScheduler.enqueueWhenOnline(context)
                // WorkManager keeps the schedule across a reboot; this only makes sure it exists.
                PeriodicSyncScheduler.schedule(context)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reschedule after boot", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
