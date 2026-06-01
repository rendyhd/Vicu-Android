package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.TaskReminder
import java.time.Instant

object DefaultReminder {

    /**
     * Synthesize a default reminder from the configured offset.
     * offsetSeconds: 0 = disabled, -1 = at due time, >0 = seconds before due.
     */
    fun build(dueDate: String?, offsetSeconds: Int, relativeTo: String): TaskReminder? {
        if (offsetSeconds == 0) return null
        if (dueDate.isNullOrBlank() || dueDate == Constants.NULL_DATE_STRING) return null

        if (offsetSeconds == -1) {
            return TaskReminder(reminder = dueDate, relativePeriod = 0, relativeTo = relativeTo)
        }
        val due = try {
            Instant.parse(dueDate)
        } catch (e: Exception) {
            return null
        }
        val trigger = due.minusSeconds(offsetSeconds.toLong())
        return TaskReminder(
            reminder = trigger.toString(),
            relativePeriod = -offsetSeconds.toLong(),
            relativeTo = relativeTo,
        )
    }
}
