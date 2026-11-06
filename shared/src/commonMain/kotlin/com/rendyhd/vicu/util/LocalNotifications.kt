package com.rendyhd.vicu.util

import com.rendyhd.vicu.data.local.DailySummaryContent
import com.rendyhd.vicu.data.local.NotificationPrefs
import com.rendyhd.vicu.data.local.SnoozeEntry
import com.rendyhd.vicu.domain.model.Task
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * A notification set ahead of time, for a platform that shows what was scheduled without running
 * the app when it fires (iOS). The platform turns it into its own request; [id] is stable, so
 * planning again replaces a notification instead of adding a second one.
 */
data class LocalNotification(
    val id: String,
    val kind: Kind,
    val title: String,
    val subtitle: String = "",
    val body: String,
    val trigger: Trigger,
    val sound: Boolean,
    val priority: NotificationBudget.Priority,
    /** What a tap or an action on it needs (see the KEY_ constants). */
    val data: Map<String, String>,
) {
    enum class Kind { REMINDER, SNOOZE, ROUTINE, SUMMARY, PLANNER_NUDGE }

    sealed interface Trigger {
        val at: Instant

        /** A moment: a task reminder rings at its instant wherever the device is. */
        data class Absolute(override val at: Instant) : Trigger

        /** A local wall-clock time: routines and summaries follow the device's clock and zone. */
        data class Local(val dateTime: LocalDateTime, override val at: Instant) : Trigger
    }

    companion object {
        const val KEY_KIND = "kind"
        const val KEY_TASK_ID = "taskId"
        const val KEY_TITLE = "title"
        const val KEY_ROUTINE_ID = "routineId"
        const val KEY_DATE = "date"
        const val KEY_SLOT_ID = "slotId"
        const val KEY_OCCURRENCE_KEY = "occurrenceKey"
    }
}

/**
 * Chooses which planned notifications are set when the platform holds only so many (iOS keeps 64
 * pending local notifications and drops the rest without telling).
 *
 * [Priority.REQUIRED] ones (a snooze the user asked for, the next summary of each slot) are taken
 * first; the soonest [Priority.NORMAL] ones fill what is left, and [Priority.FILLER] ones only the
 * room after that. Planning runs again whenever the data changes and in background refreshes, so
 * the later ones come in as the earlier ones fire.
 */
object NotificationBudget {
    /** iOS's limit of 64 less a few, so a test notification or one set by the system still fits. */
    const val IOS_CAPACITY = 60

    enum class Priority { REQUIRED, NORMAL, FILLER }

    fun select(candidates: List<LocalNotification>, capacity: Int): List<LocalNotification> {
        val byTime = compareBy<LocalNotification>({ it.trigger.at }, { it.id })
        var left = capacity
        val chosen = mutableListOf<LocalNotification>()
        for (priority in Priority.entries) {
            val taken = candidates.filter { it.priority == priority }.sortedWith(byTime).take(left.coerceAtLeast(0))
            chosen += taken
            left -= taken.size
        }
        return chosen.sortedWith(byTime)
    }
}

/**
 * Everything the app wants scheduled ahead, as [LocalNotification]s: task reminders, snoozes,
 * routine reminders and the daily summaries, within the [NotificationBudget].
 *
 * The rules follow the Android notifications: the same titles and texts, task reminders and
 * snoozes only while task reminders are on, no summary on a day with nothing to report. What
 * Android decides when an alarm fires (is the task still open, does it still have this reminder)
 * is decided here when planning, and planning runs again on every change.
 *
 * A summary's numbers are read for the day it is shown, from the data as it is now. Only the
 * next [SUMMARY_PRECISE_DAYS] days get numbers; the days after that, up to [SUMMARY_DAYS], get a
 * general summary, so the daily notification keeps coming when the app has not run for a while
 * but never shows numbers that are days old.
 */
object LocalNotificationPlanner {
    const val SUMMARY_PRECISE_DAYS = 3
    const val SUMMARY_DAYS = 7

    /** How far ahead routine reminders are planned before the budget trims them. */
    const val ROUTINE_HORIZON_DAYS = 14

    const val REMINDER_BODY = "Task reminder"
    const val GENERAL_SUMMARY_BODY = "Open Vicu to see your tasks for today."

    class Input(
        val now: Instant,
        val zone: TimeZone,
        val prefs: NotificationPrefs,
        /** Open tasks that have reminders. */
        val tasksWithReminders: List<Task>,
        val snoozes: List<SnoozeEntry>,
        /** Empty when routine reminders are off. */
        val routineAlarms: List<RoutineAlarmSpec>,
        /** The summary of one local day, read with the categories the preferences include. */
        val summaryFor: suspend (ClockDay) -> DailySummaryContent,
        /** "HH:mm" of Plan my day's evening nudge ([PlannerNudge.timeOf]); null while there is none. */
        val plannerNudge: String? = null,
    )

    suspend fun plan(input: Input, capacity: Int = NotificationBudget.IOS_CAPACITY): List<LocalNotification> =
        NotificationBudget.select(
            taskReminders(input) + snoozes(input) + routines(input) + summaries(input) + plannerNudges(input),
            capacity,
        )

    fun reminderId(taskId: Long, reminderIndex: Int) = "reminder:$taskId:$reminderIndex"

    fun snoozeId(taskId: Long) = "snooze:$taskId"

    fun routineId(occurrenceKey: String, followUp: Boolean) = "routine:$occurrenceKey:${if (followUp) 1 else 0}"

    fun summaryId(slot: String, date: kotlinx.datetime.LocalDate) = "summary:$slot:$date"

    fun plannerNudgeId(date: kotlinx.datetime.LocalDate) = "plan-nudge:$date"

    private fun taskReminders(input: Input): List<LocalNotification> {
        if (!input.prefs.taskRemindersEnabled) return emptyList()
        val now = input.now.toEpochMilliseconds()
        return input.tasksWithReminders.flatMap { task ->
            ReminderAlarms.desiredAlarms(task, now).map { alarm ->
                LocalNotification(
                    id = reminderId(task.id, alarm.reminderIndex),
                    kind = LocalNotification.Kind.REMINDER,
                    title = task.title.ifBlank { "Task Reminder" },
                    body = REMINDER_BODY,
                    trigger = LocalNotification.Trigger.Absolute(Instant.fromEpochMilliseconds(alarm.triggerAtMillis)),
                    sound = input.prefs.soundEnabled,
                    priority = NotificationBudget.Priority.NORMAL,
                    data = taskData(LocalNotification.Kind.REMINDER, task.id, task.title),
                )
            }
        }
    }

    /** A snooze rings once; one whose time has passed has rung and is not set again. */
    private fun snoozes(input: Input): List<LocalNotification> {
        if (!input.prefs.taskRemindersEnabled) return emptyList()
        return input.snoozes
            .filter { Instant.fromEpochMilliseconds(it.triggerAtMillis) > input.now }
            .map { entry ->
                LocalNotification(
                    id = snoozeId(entry.taskId),
                    kind = LocalNotification.Kind.SNOOZE,
                    title = entry.title.ifBlank { "Task Reminder" },
                    body = REMINDER_BODY,
                    trigger = LocalNotification.Trigger.Absolute(Instant.fromEpochMilliseconds(entry.triggerAtMillis)),
                    sound = input.prefs.soundEnabled,
                    priority = NotificationBudget.Priority.REQUIRED,
                    data = taskData(LocalNotification.Kind.SNOOZE, entry.taskId, entry.title),
                )
            }
    }

    private fun routines(input: Input): List<LocalNotification> = input.routineAlarms
        .filter { it.triggerAt > input.now }
        .map { alarm ->
            LocalNotification(
                id = routineId(alarm.occurrenceKey, alarm.followUp),
                kind = LocalNotification.Kind.ROUTINE,
                title = if (alarm.followUp) "Still time for ${alarm.title}" else alarm.title,
                body = if (alarm.followUp) "Tap Complete when you’ve done it" else "Routine reminder",
                trigger = LocalNotification.Trigger.Local(alarm.localTriggerAt, alarm.triggerAt),
                // Android's routine channel uses the default sound.
                sound = true,
                priority = NotificationBudget.Priority.NORMAL,
                data = mapOf(
                    LocalNotification.KEY_KIND to LocalNotification.Kind.ROUTINE.name,
                    LocalNotification.KEY_ROUTINE_ID to alarm.routineId,
                    LocalNotification.KEY_DATE to alarm.date,
                    LocalNotification.KEY_SLOT_ID to alarm.slotId,
                    LocalNotification.KEY_OCCURRENCE_KEY to alarm.occurrenceKey,
                ),
            )
        }

    private suspend fun summaries(input: Input): List<LocalNotification> {
        val prefs = input.prefs
        val slots = listOf(
            Triple(DailySummary.SLOT_MORNING, prefs.dailySummaryEnabled, prefs.dailySummaryHour to prefs.dailySummaryMinute),
            Triple(DailySummary.SLOT_AFTERNOON, prefs.afternoonSummaryEnabled, prefs.afternoonSummaryHour to prefs.afternoonSummaryMinute),
        )
        val today = input.now.toLocalDateTime(input.zone).date
        val result = mutableListOf<LocalNotification>()
        for ((slot, enabled, time) in slots) {
            if (!enabled) continue
            val localTime = LocalTime(time.first.coerceIn(0, 23), time.second.coerceIn(0, 59))
            var first = true
            for (offset in 0 until SUMMARY_DAYS) {
                val date = today.plus(offset, DateTimeUnit.DAY)
                val local = LocalDateTime(date, localTime)
                val at = local.toInstant(input.zone)
                if (at <= input.now) continue
                val precise = offset < SUMMARY_PRECISE_DAYS
                val (subtitle, body) = if (precise) {
                    val content = input.summaryFor(ClockDay(date, input.zone))
                    // As on Android: a day with nothing to report gets no summary.
                    if (content.counts.total == 0) continue
                    DailySummary.headline(content.counts) to content.lines().joinToString("\n")
                } else {
                    "" to GENERAL_SUMMARY_BODY
                }
                result += LocalNotification(
                    id = summaryId(slot, date),
                    kind = LocalNotification.Kind.SUMMARY,
                    title = DailySummary.heading(slot),
                    subtitle = subtitle,
                    body = body,
                    trigger = LocalNotification.Trigger.Local(local, at),
                    sound = prefs.soundEnabled,
                    priority = when {
                        first -> NotificationBudget.Priority.REQUIRED
                        precise -> NotificationBudget.Priority.NORMAL
                        else -> NotificationBudget.Priority.FILLER
                    },
                    data = mapOf(LocalNotification.KEY_KIND to LocalNotification.Kind.SUMMARY.name),
                )
                first = false
            }
        }
        return result
    }

    /** One each evening for a week, so it keeps coming when the app has not run for a while. */
    private fun plannerNudges(input: Input): List<LocalNotification> {
        val clock = input.plannerNudge ?: return emptyList()
        val minutes = TimeBlocks.clockMinutes(clock)
        val time = LocalTime(minutes / 60, minutes % 60)
        val today = input.now.toLocalDateTime(input.zone).date
        var first = true
        return (0 until SUMMARY_DAYS).mapNotNull { offset ->
            val date = today.plus(offset, DateTimeUnit.DAY)
            val local = LocalDateTime(date, time)
            val at = local.toInstant(input.zone)
            if (at <= input.now) return@mapNotNull null
            LocalNotification(
                id = plannerNudgeId(date),
                kind = LocalNotification.Kind.PLANNER_NUDGE,
                title = PlannerNudge.TITLE,
                body = PlannerNudge.BODY,
                trigger = LocalNotification.Trigger.Local(local, at),
                sound = input.prefs.soundEnabled,
                priority = if (first) NotificationBudget.Priority.REQUIRED else NotificationBudget.Priority.NORMAL,
                data = mapOf(LocalNotification.KEY_KIND to LocalNotification.Kind.PLANNER_NUDGE.name),
            ).also { first = false }
        }
    }

    private fun taskData(kind: LocalNotification.Kind, taskId: Long, title: String) = mapOf(
        LocalNotification.KEY_KIND to kind.name,
        LocalNotification.KEY_TASK_ID to taskId.toString(),
        LocalNotification.KEY_TITLE to title,
    )
}
