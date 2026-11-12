package com.rendyhd.vicu.util.pro

import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.TimeBlocks
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/** One of Upcoming's "starts" entries: an open task and the later local day it starts on. */
data class StartsEntry(val id: Long, val day: LocalDate)

/**
 * Start dates (docs/cross-app-semantics-v1.md section 21.3), the same as the desktop's
 * `src/shared/pro/start-dates.ts`: a task's `start_date` when it is not part of a time block
 * (section 16.1). A block replaces a start date: the block's start is written to the same field.
 */
object StartDates {
    /** The task's start date, or null when it has none or its dates are a time block. */
    fun startDateOf(startDate: String?, endDate: String?, zone: TimeZone): Instant? {
        if (TimeBlocks.isBlock(startDate, endDate, zone)) return null
        return instantOf(startDate)
    }

    fun startDateOf(task: Task, zone: TimeZone): Instant? = startDateOf(task.startDate, task.endDate, zone)

    /** The local day the task starts on, or null. */
    fun startDayOf(task: Task, zone: TimeZone): LocalDate? = startDateOf(task, zone)?.toLocalDateTime(zone)?.date

    /** True while the task has a start date later than [now] (it is not available yet). */
    fun startsLater(task: Task, now: Instant, zone: TimeZone): Boolean {
        val start = startDateOf(task, zone) ?: return false
        return start > now
    }

    /**
     * The stored value of "starts <day>" without a time: the local midnight that begins the day, so
     * the task is available all of that day.
     */
    fun dateOnlyStart(day: LocalDate, zone: TimeZone): Instant = LocalDateTime(day, LocalTime(0, 0)).toInstant(zone)

    /**
     * Decision 17: placing a block on a task whose start date is later than the block's start
     * replaces that start date. The UI warns first; true when it should.
     */
    fun blockReplacesLaterStart(task: Task, blockStart: Instant, zone: TimeZone): Boolean {
        val start = startDateOf(task, zone) ?: return false
        return start > blockStart
    }

    /**
     * Upcoming's "starts" entries (Pro on): open tasks whose start date is on a later local day than
     * [now], by day, then id.
     */
    fun startsEntries(tasks: List<Task>, now: Instant, zone: TimeZone): List<StartsEntry> {
        val today = now.toLocalDateTime(zone).date
        return tasks
            .filter { !it.done }
            .mapNotNull { task -> startDayOf(task, zone)?.takeIf { it > today }?.let { StartsEntry(task.id, it) } }
            .sortedWith(compareBy<StartsEntry> { it.day }.thenBy { it.id })
    }
}
