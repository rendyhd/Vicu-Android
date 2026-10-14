package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.TaskReminder
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

object ReminderFormat {
    /** Absolute reminder time, in the user's local timezone and the app's date style ("Jan 1, 2030, 9:00 AM"). */
    fun formatAbsolute(dateStr: String, fmt: DateDisplayFormat): String {
        val instant = DateUtils.parseIsoDate(dateStr) ?: return dateStr
        return DateDisplay.formatAbsolute(instant.toLocalDateTime(TimeZone.currentSystemDefault()), fmt)
    }

    /**
     * Human label for a reminder: an absolute time, or what a relative one counts from, such as
     * "15 minutes before" (the due date), "At start" or "1 day before end".
     */
    fun format(reminder: TaskReminder, fmt: DateDisplayFormat): String {
        if (reminder.reminder.isNotBlank() && !DateUtils.isNullDate(reminder.reminder)) {
            return formatAbsolute(reminder.reminder, fmt)
        }
        return formatRelative(reminder.relativePeriod, reminder.relativeTo)
    }

    /**
     * A relative reminder: [seconds] from the date [relativeTo] names (`due_date`, `start_date`,
     * `end_date`; blank means the due date, as it always did). The label says which date, so a
     * reminder at the start of a task is not read as one at its due time.
     */
    internal fun formatRelative(seconds: Long, relativeTo: String): String {
        val name = relativeTo.trim()
        // "At ..." for an offset of zero, and what follows "before"/"after" otherwise.
        val (at, after) = when (name.lowercase()) {
            "", "due_date" -> "At due time" to ""
            "start_date" -> "At start" to " start"
            "end_date" -> "At end" to " end"
            else -> "At $name" to " $name"
        }
        if (seconds == 0L) return at
        return "${duration(kotlin.math.abs(seconds))} ${if (seconds < 0) "before" else "after"}$after"
    }

    /** The largest whole unit: "1 day", "2 hours", "90 minutes". */
    private fun duration(seconds: Long): String = when {
        seconds % 86_400 == 0L -> plural(seconds / 86_400, "day")
        seconds % 3_600 == 0L -> plural(seconds / 3_600, "hour")
        seconds % 60 == 0L -> plural(seconds / 60, "minute")
        else -> plural(seconds, "second")
    }

    private fun plural(count: Long, unit: String): String = if (count == 1L) "1 $unit" else "$count ${unit}s"

    /** Summary for a row: the single reminder's label, or "N reminders" when there are several. */
    fun summary(reminders: List<TaskReminder>, fmt: DateDisplayFormat): String = when (reminders.size) {
        0 -> ""
        1 -> format(reminders.first(), fmt)
        else -> "${reminders.size} reminders"
    }
}
