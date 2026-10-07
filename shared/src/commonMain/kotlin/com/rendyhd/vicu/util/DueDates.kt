package com.rendyhd.vicu.util

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * The one due-date policy (docs/cross-app-semantics-v1.md, sections 1, 2 and 4). Every place that
 * sets, classifies, queries or displays a due date goes through here, so the same server value
 * means the same thing on every screen and on desktop.
 *
 * - A due date without a time of day ("date-only") is stored as local 23:59:59.000 of its date.
 * - Legacy values at local 00:00:00 are read as date-only as well and are not rewritten in bulk;
 *   they become 23:59:59 the next time the user sets the date.
 * - Any other local time is an explicit time and is kept as it is.
 * - "Today" is the local calendar date (from [DayClock]); weeks start on Monday.
 *
 * Everything here is pure: the zone and the day are always passed in, never read from the system
 * clock, so behavior is the same in tests, in workers and in the UI.
 */
object DueDates {

    /** The time of day a date-only due date is stored at. */
    val DATE_ONLY_TIME: LocalTime = LocalTime(23, 59, 59)

    /** Where a due date sits relative to a local "today", whatever its time of day. */
    enum class Bucket { NONE, OVERDUE, TODAY, UPCOMING }

    // --- Date-only values ---------------------------------------------------------------

    /** The due date for "this date, no time": local 23:59:59.000 of [date]. */
    fun dateOnlyDue(date: LocalDate, zone: TimeZone): Instant =
        LocalDateTime(date, DATE_ONLY_TIME).toInstant(zone)

    /** The local calendar date [instant] falls on in [zone] (never the UTC date). */
    fun localDateOf(instant: Instant, zone: TimeZone): LocalDate = instant.toLocalDateTime(zone).date

    /** The local calendar date of a stored due date, or null when there is none. */
    fun localDateOf(dueDate: String?, zone: TimeZone): LocalDate? =
        DateUtils.parseIsoDate(dueDate)?.let { localDateOf(it, zone) }

    /**
     * Whether [instant] carries no time of day: local 23:59:59 or the legacy local 00:00:00.
     * Milliseconds are ignored.
     */
    fun isDateOnly(instant: Instant, zone: TimeZone): Boolean {
        val t = instant.toLocalDateTime(zone).time
        return (t.hour == 23 && t.minute == 59 && t.second == 59) ||
            (t.hour == 0 && t.minute == 0 && t.second == 0)
    }

    fun isDateOnly(dueDate: String?, zone: TimeZone): Boolean =
        DateUtils.parseIsoDate(dueDate)?.let { isDateOnly(it, zone) } ?: false

    // --- Weeks and months (Monday-start weeks) ------------------------------------------

    /** The Monday of the week containing [date]. */
    fun startOfWeek(date: LocalDate): LocalDate = date.minus(date.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)

    /** The Sunday ending the week that contains [date] (on a Sunday: [date] itself). */
    fun endOfWeek(date: LocalDate): LocalDate = date.plus(7 - date.dayOfWeek.isoDayNumber, DateTimeUnit.DAY)

    /** The Monday after [date]: strictly in the future, so a Monday gives the next week's Monday. */
    fun nextWeekStart(date: LocalDate): LocalDate = endOfWeek(date).plus(1, DateTimeUnit.DAY)

    /** The last day of the month [date] is in. */
    fun endOfMonth(date: LocalDate): LocalDate =
        LocalDate(date.year, date.monthNumber, 1).plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY)

    // --- Setters ------------------------------------------------------------------------

    /** "Today": today's local date, date-only. */
    fun today(today: LocalDate, zone: TimeZone): Instant = dateOnlyDue(today, zone)

    /** "Tomorrow": tomorrow's local date, date-only. */
    fun tomorrow(today: LocalDate, zone: TimeZone): Instant =
        dateOnlyDue(today.plus(1, DateTimeUnit.DAY), zone)

    /** "Next week": the Monday of next week (on a Sunday: the next day), date-only. */
    fun nextWeek(today: LocalDate, zone: TimeZone): Instant = dateOnlyDue(nextWeekStart(today), zone)

    /** "Pick a date": the picked local calendar date, date-only. Never built from UTC millis. */
    fun pickDate(date: LocalDate, zone: TimeZone): Instant = dateOnlyDue(date, zone)

    /** The "!" shortcut: today, date-only. */
    fun bang(today: LocalDate, zone: TimeZone): Instant = dateOnlyDue(today, zone)

    /**
     * Postpone / move by [days] calendar days. An explicit time of day is kept; a date-only value
     * (including a legacy 00:00 one) stays date-only and becomes 23:59:59. Without a due date the
     * count starts from [today].
     */
    fun postponeDays(due: Instant?, days: Int, today: LocalDate, zone: TimeZone): Instant {
        if (due == null || isDateOnly(due, zone)) {
            val base = due?.let { localDateOf(it, zone) } ?: today
            return dateOnlyDue(base.plus(days, DateTimeUnit.DAY), zone)
        }
        val local = due.toLocalDateTime(zone)
        return LocalDateTime(local.date.plus(days, DateTimeUnit.DAY), local.time).toInstant(zone)
    }

    /**
     * The due date for a date parsed from free text. When the text named a time of day
     * ([hasTime]) that time is kept to the minute; otherwise the date is date-only.
     */
    fun fromParsed(parsed: LocalDateTime, hasTime: Boolean, zone: TimeZone): Instant =
        if (hasTime) {
            LocalDateTime(parsed.date, LocalTime(parsed.hour, parsed.minute)).toInstant(zone)
        } else {
            dateOnlyDue(parsed.date, zone)
        }

    // --- Date picker (Material 3 reads and returns a UTC day) ---------------------------

    /**
     * The value to pre-select in the Material date picker for a stored due date: the UTC midnight
     * of its **local** calendar date, because the picker shows the UTC day of the millis. Null when
     * there is no due date.
     */
    fun datePickerMillis(dueDate: String?, zone: TimeZone): Long? =
        localDateOf(dueDate, zone)?.atStartOfDayIn(TimeZone.UTC)?.toEpochMilliseconds()

    /** The calendar date the Material date picker returned (it reports UTC midnight of that day). */
    fun dateFromDatePickerMillis(millis: Long): LocalDate =
        Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date

    // --- Today / Upcoming classification ------------------------------------------------

    fun bucket(due: Instant?, today: LocalDate, zone: TimeZone): Bucket {
        if (due == null) return Bucket.NONE
        val date = localDateOf(due, zone)
        return when {
            date < today -> Bucket.OVERDUE
            date == today -> Bucket.TODAY
            else -> Bucket.UPCOMING
        }
    }

    fun bucket(dueDate: String?, today: LocalDate, zone: TimeZone): Bucket =
        bucket(DateUtils.parseIsoDate(dueDate), today, zone)

    /** Local date before today. A task due at 08:00 today is not overdue at 10:00; tomorrow it is. */
    fun isOverdue(dueDate: String?, today: LocalDate, zone: TimeZone): Boolean =
        bucket(dueDate, today, zone) == Bucket.OVERDUE

    /** Local date equal to today, whatever the time of day. */
    fun isDueToday(dueDate: String?, today: LocalDate, zone: TimeZone): Boolean =
        bucket(dueDate, today, zone) == Bucket.TODAY

    /** Local date tomorrow or later. */
    fun isUpcoming(dueDate: String?, today: LocalDate, zone: TimeZone): Boolean =
        bucket(dueDate, today, zone) == Bucket.UPCOMING

    // --- Query boundaries ---------------------------------------------------------------

    /** Local midnight at the start of [date] in [zone]. */
    fun startOfDay(date: LocalDate, zone: TimeZone): Instant = date.atStartOfDayIn(zone)

    /**
     * Local midnight at the start of the day after [today]: the exclusive end of today. Today is
     * `dueDate < startOfTomorrow` and Upcoming is `dueDate >= startOfTomorrow`.
     */
    fun startOfTomorrow(today: LocalDate, zone: TimeZone): Instant =
        startOfDay(today.plus(1, DateTimeUnit.DAY), zone)
}
