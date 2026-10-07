package com.rendyhd.vicu.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import com.rendyhd.vicu.data.local.RoutinePrefsStore
import com.rendyhd.vicu.domain.model.OccurrenceStatus
import com.rendyhd.vicu.domain.repository.RoutineRepository
import com.rendyhd.vicu.util.RoutineScheduleEngine
import com.rendyhd.vicu.util.TimeSource
import kotlinx.coroutines.flow.first
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.toInstant

class RoutineAlarmScheduler(
    private val context: Context,
    private val repository: RoutineRepository,
    private val prefsStore: RoutinePrefsStore,
    private val time: TimeSource,
) {
    companion object {
        private const val TAG = "RoutineAlarmScheduler"
        private const val HORIZON_DAYS = 45
        private const val PREFS = "routine_alarm_ids"
        private const val KEY_IDS = "request_codes"
    }

    private val alarmManager: AlarmManager get() = context.getSystemService(AlarmManager::class.java)

    suspend fun rescheduleAll() {
        cancelAll()
        if (!prefsStore.remindersEnabled.first()) return

        val timeZone = time.zone()
        val nowInstant = time.now()
        val today = nowInstant.toLocalDateTime(timeZone).date
        val now = nowInstant.toEpochMilliseconds()
        val requestCodes = mutableSetOf<String>()
        val routines = repository.observeActive().first()

        routines.forEach { routine ->
            for (offset in 0..HORIZON_DAYS) {
                val date = today.plus(offset, DateTimeUnit.DAY)
                RoutineScheduleEngine.occurrencesForDate(routine, date, timeZone, today)
                    .filter { it.status == OccurrenceStatus.PENDING && it.slot.reminderEnabled }
                    .forEach { occurrence ->
                        val hour = (occurrence.slot.reminderMinutes / 60).coerceIn(0, 23)
                        val minute = (occurrence.slot.reminderMinutes % 60).coerceIn(0, 59)
                        val trigger = LocalDateTime(date, LocalTime(hour, minute)).toInstant(timeZone).toEpochMilliseconds()
                        if (trigger > now) {
                            schedule(occurrence.key, routine.definition.name, occurrence.scheduledDate, occurrence.slot.id, trigger, false)
                            requestCodes += requestCode(occurrence.key, false).toString()
                            if (occurrence.slot.followUpMinutes > 0) {
                                val followUp = trigger + occurrence.slot.followUpMinutes * 60_000L
                                schedule(occurrence.key, routine.definition.name, occurrence.scheduledDate, occurrence.slot.id, followUp, true)
                                requestCodes += requestCode(occurrence.key, true).toString()
                            }
                        }
                    }
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putStringSet(KEY_IDS, requestCodes) }
        Log.d(TAG, "Scheduled ${requestCodes.size} routine alarms")
    }

    private fun schedule(
        occurrenceKey: String,
        title: String,
        date: String,
        slotId: String,
        triggerAtMillis: Long,
        followUp: Boolean,
    ) {
        val intent = Intent(context, RoutineAlarmReceiver::class.java).apply {
            putExtra(RoutineAlarmReceiver.EXTRA_OCCURRENCE_KEY, occurrenceKey)
            putExtra(RoutineAlarmReceiver.EXTRA_ROUTINE_ID, occurrenceKey.substringBefore(':'))
            putExtra(RoutineAlarmReceiver.EXTRA_DATE, date)
            putExtra(RoutineAlarmReceiver.EXTRA_SLOT_ID, slotId)
            putExtra(RoutineAlarmReceiver.EXTRA_TITLE, title)
            putExtra(RoutineAlarmReceiver.EXTRA_FOLLOW_UP, followUp)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode(occurrenceKey, followUp),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
        }
    }

    /** Cancels every routine alarm that is registered (sign-out, account switch). */
    fun cancelAll() {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getStringSet(KEY_IDS, emptySet()).orEmpty().forEach { raw ->
            val requestCode = raw.toIntOrNull() ?: return@forEach
            val pending = PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, RoutineAlarmReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            if (pending != null) {
                alarmManager.cancel(pending)
                pending.cancel()
            }
        }
        prefs.edit { remove(KEY_IDS) }
    }

    private fun requestCode(occurrenceKey: String, followUp: Boolean): Int =
        "routine:$occurrenceKey:${if (followUp) 1 else 0}".hashCode()
}
