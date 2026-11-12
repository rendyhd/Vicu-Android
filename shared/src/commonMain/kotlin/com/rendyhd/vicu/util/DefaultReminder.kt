package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.TaskReminder
import kotlin.time.Instant
import kotlin.time.Duration.Companion.seconds

object DefaultReminder {

    /**
     * Synthesize a default reminder from the configured offset.
     * offsetSeconds: 0 = disabled, -1 = at due time, >0 = seconds before due.
     *
     * A new task with a time block goes by the block instead (section 16.5 of the contract):
     * [blockStart] and [blockEnd] are its two ends when it has one, and "relative to the due date"
     * becomes relative to its start, because its due date is date-only and would remind at 22:59:59.
     */
    fun build(
        dueDate: String?,
        offsetSeconds: Int,
        relativeTo: String,
        blockStart: String? = null,
        blockEnd: String? = null,
    ): TaskReminder? {
        if (offsetSeconds == 0) return null
        if (dueDate.isNullOrBlank() || dueDate == Constants.NULL_DATE_STRING) return null

        // Both ends make a block: a lone start date (contract 21.3) is no block, so it never moves
        // the reminder off the due date.
        val blockFrom = DateUtils.parseIsoDate(blockStart?.takeIf { it.isNotBlank() })
        val blockTo = DateUtils.parseIsoDate(blockEnd?.takeIf { it.isNotBlank() })
        val hasBlock = blockFrom != null && blockTo != null && blockTo > blockFrom
        val to = if (hasBlock && relativeTo == "due_date") "start_date" else relativeTo
        val base: String = when {
            !hasBlock -> dueDate
            to == "end_date" -> blockEnd.orEmpty()
            else -> blockStart.orEmpty()
        }
        if (offsetSeconds == -1) {
            return TaskReminder(reminder = base, relativePeriod = 0, relativeTo = to)
        }
        val from = try {
            Instant.parse(base)
        } catch (e: Exception) {
            return null
        }
        val trigger = from - offsetSeconds.seconds
        return TaskReminder(
            reminder = trigger.toString(),
            relativePeriod = -offsetSeconds.toLong(),
            relativeTo = to,
        )
    }
}
