package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.TaskReminder
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Shared formatting for reminders, used by both the reminder picker and the task detail row,
 * so they show the actual time / relative period instead of a bare count.
 */
object ReminderFormat {
    private val dateFmt = DateTimeFormatter.ofPattern("MMM d, yyyy")
    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a")

    private val relativeLabels = mapOf(
        0L to "At due time",
        -300L to "5 minutes before",
        -900L to "15 minutes before",
        -3600L to "1 hour before",
        -86400L to "1 day before",
    )

    /** Absolute reminder time, in the user's local timezone. */
    fun formatAbsolute(dateStr: String): String {
        val instant = DateUtils.parseIsoDate(dateStr) ?: return dateStr
        val zoned = instant.atZone(ZoneId.systemDefault())
        return "${zoned.toLocalDate().format(dateFmt)} ${zoned.toLocalTime().format(timeFmt)}"
    }

    /** Human label for a reminder: an absolute time, or a relative-to-due description. */
    fun format(reminder: TaskReminder): String {
        if (reminder.reminder.isNotBlank() && !DateUtils.isNullDate(reminder.reminder)) {
            return formatAbsolute(reminder.reminder)
        }
        return relativeLabels[reminder.relativePeriod]
            ?: if (reminder.relativePeriod != 0L) "${reminder.relativePeriod / 60} min relative" else "At due time"
    }

    /** Summary for a row: the single reminder's label, or "N reminders" when there are several. */
    fun summary(reminders: List<TaskReminder>): String = when (reminders.size) {
        0 -> ""
        1 -> format(reminders.first())
        else -> "${reminders.size} reminders"
    }
}
