package com.rendyhd.vicu.notification

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.rendyhd.vicu.MainActivity
import com.rendyhd.vicu.putViewTarget
import com.rendyhd.vicu.ui.navigation.ViewTarget
import com.rendyhd.vicu.R
import com.rendyhd.vicu.data.local.RoutinePrefsStore
import com.rendyhd.vicu.domain.model.OccurrenceStatus
import com.rendyhd.vicu.domain.repository.RoutineRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class RoutineAlarmReceiver : BroadcastReceiver(), KoinComponent {
    companion object {
        const val EXTRA_OCCURRENCE_KEY = "routine_occurrence_key"
        const val EXTRA_ROUTINE_ID = "routine_id"
        const val EXTRA_DATE = "routine_date"
        const val EXTRA_SLOT_ID = "routine_slot_id"
        const val EXTRA_TITLE = "routine_title"
        const val EXTRA_FOLLOW_UP = "routine_follow_up"
    }

    private val repository: RoutineRepository by inject()
    private val prefsStore: RoutinePrefsStore by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val routineId = intent.getStringExtra(EXTRA_ROUTINE_ID) ?: return
        val date = intent.getStringExtra(EXTRA_DATE) ?: return
        val slotId = intent.getStringExtra(EXTRA_SLOT_ID) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Routine"
        val key = intent.getStringExtra(EXTRA_OCCURRENCE_KEY) ?: "$routineId:$date:$slotId"
        val followUp = intent.getBooleanExtra(EXTRA_FOLLOW_UP, false)

        val occurrence = runBlocking {
            // An alarm left over from before routines were turned off stays quiet.
            if (!prefsStore.enabled.first()) return@runBlocking null
            repository.observeDay(date).first().occurrences.firstOrNull { it.key == key }
        } ?: return
        if (occurrence.status == OccurrenceStatus.COMPLETED || occurrence.status == OccurrenceStatus.SKIPPED) return

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putViewTarget(ViewTarget.Routines)
        }
        val openPending = PendingIntent.getActivity(
            context,
            "open:$key".hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        fun actionPending(action: String): PendingIntent {
            val actionIntent = Intent(context, RoutineNotificationActionReceiver::class.java).apply {
                this.action = action
                putExtra(EXTRA_ROUTINE_ID, routineId)
                putExtra(EXTRA_DATE, date)
                putExtra(EXTRA_SLOT_ID, slotId)
                putExtra(EXTRA_OCCURRENCE_KEY, key)
            }
            return PendingIntent.getBroadcast(
                context,
                "$action:$key".hashCode(),
                actionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val notification = NotificationCompat.Builder(context, NotificationChannelManager.CHANNEL_ROUTINES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (followUp) "Still time for $title" else title)
            .setContentText(if (followUp) "Tap Complete when you’ve done it" else "Routine reminder")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openPending)
            .addAction(0, "Complete", actionPending(RoutineNotificationActionReceiver.ACTION_COMPLETE))
            .addAction(0, "Skip", actionPending(RoutineNotificationActionReceiver.ACTION_SKIP))
            .build()
        try {
            NotificationManagerCompat.from(context).notify("routine:$key".hashCode(), notification)
        } catch (_: SecurityException) {
            // Notification permission may have been declined; alarms stay scheduled.
        }
    }
}
