package com.rendyhd.vicu.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rendyhd.vicu.data.local.NotificationPrefsStore
import com.rendyhd.vicu.widget.WidgetUpdateScheduler
import com.rendyhd.vicu.worker.SyncScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    @Inject lateinit var alarmScheduler: AlarmScheduler
    @Inject lateinit var dailySummaryScheduler: DailySummaryScheduler
    @Inject lateinit var prefsStore: NotificationPrefsStore

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.d(TAG, "Boot completed — rescheduling alarms")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                alarmScheduler.rescheduleAll()
                val prefs = prefsStore.getPrefs().first()
                dailySummaryScheduler.scheduleIfEnabled(
                    prefs.dailySummaryEnabled,
                    prefs.dailySummaryHour,
                    prefs.dailySummaryMinute,
                )
                WidgetUpdateScheduler.enqueueImmediateUpdateAll(context)
                SyncScheduler.enqueueWhenOnline(context)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reschedule after boot", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
