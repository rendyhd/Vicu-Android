package com.rendyhd.vicu.ui.screens.taskdetail

import com.rendyhd.vicu.domain.model.TaskReminder
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.DateDisplayFormat
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.util.WeekStart
import com.rendyhd.vicu.util.WhenLogic
import com.rendyhd.vicu.util.WhenQuickChoice
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/** The most quick moves the editor shows under the notes. */
internal const val MAX_QUICK_MOVES = 3

/**
 * The date moves the editor offers above the notes (design concept 4, "Calm editor"): the When
 * sheet's quick choices (Today, Tomorrow, This weekend, Next week), without the day the task is
 * already due and without a second choice for the same day (on a Friday, Tomorrow and This weekend
 * are both Saturday), at most [MAX_QUICK_MOVES]. An overdue task so gets Today, Tomorrow and This
 * weekend; a task due today gets the three later ones.
 */
internal fun quickMoves(
    due: String?,
    today: LocalDate,
    zone: TimeZone,
    weekStart: WeekStart = WeekStart.DEFAULT,
): List<WhenQuickChoice> {
    val dueDay = WhenLogic.valueOfDue(due, zone).date
    return WhenLogic.quickChoices(today, weekStart)
        .filter { it.date != dueDay }
        .distinctBy { it.date }
        .take(MAX_QUICK_MOVES)
}

/**
 * [due] moved to [date] with its time of day kept, as a stored due date. A date-only due date
 * stays date-only, and a task without one gets a date-only due date ([DueDates]).
 */
internal fun movedDue(due: String?, date: LocalDate, zone: TimeZone): String {
    val moved = WhenLogic.withDate(WhenLogic.valueOfDue(due, zone), date)
    return requireNotNull(WhenLogic.dueOf(moved, zone)).toString()
}

/** What a quick move says on the app snackbar: "Due tomorrow", "Due this weekend". */
internal fun quickMoveMessage(choice: WhenQuickChoice): String =
    "Due " + choice.label.replaceFirstChar { it.lowercase() }

/** A reminder the editor offers in place, and what its chip says. */
internal data class ReminderPreset(val label: String, val reminder: TaskReminder)

/** The relative presets: they count from the due date, so they are offered only when there is one. */
private val RELATIVE_PRESETS = listOf(
    "At due time" to 0L,
    "15 minutes before" to -15 * 60L,
    "1 hour before" to -60 * 60L,
    "1 day before" to -24 * 60 * 60L,
)

/** The evening preset is offered until this hour; after it the evening is too close to plan for. */
private const val EVENING_UNTIL_HOUR = 17
private val EVENING = LocalTime(18, 0)
private val MORNING = LocalTime(9, 0)

/**
 * The reminders the editor's Remind row offers without a dialog, at [now] in [zone]:
 * "In 1 hour" (rounded up to the next 5 minutes), "This evening, 18:00" (before 17:00),
 * "Tomorrow, 09:00", and with a due date ([due]) the relative ones that count from it.
 * Times are phrased with [fmt] (12 or 24 hour clock).
 */
internal fun reminderPresets(due: String?, now: Instant, zone: TimeZone, fmt: DateDisplayFormat): List<ReminderPreset> {
    val local = now.toLocalDateTime(zone)
    val presets = mutableListOf<ReminderPreset>()

    val inAnHour = roundUpToFiveMinutes(now + 1.hours, zone)
    presets += ReminderPreset("In 1 hour", TaskReminder(reminder = inAnHour.toString()))
    if (local.hour < EVENING_UNTIL_HOUR) {
        val evening = LocalDateTime(local.date, EVENING).toInstant(zone)
        presets += ReminderPreset(
            "This evening, ${DateDisplay.formatTime(EVENING, fmt)}",
            TaskReminder(reminder = evening.toString()),
        )
    }
    val tomorrowMorning = LocalDateTime(local.date.plus(1, DateTimeUnit.DAY), MORNING).toInstant(zone)
    presets += ReminderPreset(
        "Tomorrow, ${DateDisplay.formatTime(MORNING, fmt)}",
        TaskReminder(reminder = tomorrowMorning.toString()),
    )

    val hasDue = WhenLogic.valueOfDue(due, zone).date != null
    if (hasDue) {
        RELATIVE_PRESETS.forEach { (label, seconds) ->
            presets += ReminderPreset(label, TaskReminder(relativePeriod = seconds, relativeTo = "due_date"))
        }
    }
    return presets
}

/** [instant] moved up to the next whole 5 minutes of the local clock (seconds dropped). */
private fun roundUpToFiveMinutes(instant: Instant, zone: TimeZone): Instant {
    val local = instant.toLocalDateTime(zone)
    val floored = LocalDateTime(local.date, LocalTime(local.hour, local.minute)).toInstant(zone)
    val extra = (5 - local.minute % 5) % 5
    val exact = extra == 0 && local.second == 0 && local.nanosecond == 0
    return if (exact) floored else floored + (if (extra == 0) 5 else extra).minutes
}
