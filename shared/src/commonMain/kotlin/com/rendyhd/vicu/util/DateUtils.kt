package com.rendyhd.vicu.util

import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toJavaLocalTime
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import kotlinx.datetime.atStartOfDayIn
import kotlin.time.Duration
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

object DateUtils {
    private val localZone: TimeZone get() = TimeZone.currentSystemDefault()

    fun isNullDate(dateStr: String?): Boolean {
        return dateStr.isNullOrBlank() || dateStr == Constants.NULL_DATE_STRING
    }

    /** Milliseconds from [now] to the next local midnight; never less than one second. */
    fun millisUntilNextMidnight(
        now: Instant = Clock.System.now(),
        timeZone: TimeZone = localZone
    ): Long {
        val today = now.toLocalDateTime(timeZone).date
        val nextMidnight = today.plus(1, DateTimeUnit.DAY).atStartOfDayIn(timeZone)
        return (nextMidnight - now).inWholeMilliseconds.coerceAtLeast(1_000L)
    }


    fun nowIso(): String {
        return Clock.System.now().toString()
    }

    /** ISO-8601 UTC timestamp for [days] days before now (used for logbook retention cutoffs). */
    fun isoDaysAgo(days: Int): String {
        return Clock.System.now().minus(days.toLong(), DateTimeUnit.DAY, localZone).toString()
    }

    fun parseIsoDate(dateStr: String?): Instant? {
        if (isNullDate(dateStr)) return null
        return try {
            Instant.parse(dateStr!!)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Normalize any ISO date string to UTC "Z" format for consistent Room string comparisons.
     * E.g. "2026-02-21T23:59:59+01:00" → "2026-02-21T22:59:59Z"
     */
    fun normalizeToUtc(dateStr: String): String {
        if (dateStr.isBlank() || dateStr == Constants.NULL_DATE_STRING) return dateStr
        if (dateStr.endsWith("Z")) return dateStr // Already UTC
        return try {
            val instant = Instant.parse(dateStr)
            instant.toString()
        } catch (_: Exception) {
            dateStr
        }
    }

    /**
     * Local date before [today]. [today] defaults to the system clock; pass the day from DayClock
     * so labels follow midnight. The rule itself lives in [DueDates].
     */
    fun isOverdue(
        dateStr: String?,
        today: LocalDate = Clock.System.todayIn(localZone),
        zone: TimeZone = localZone,
    ): Boolean = DueDates.isOverdue(dateStr, today, zone)

    /** Local date equal to [today], whatever the time of day. */
    fun isToday(
        dateStr: String?,
        today: LocalDate = Clock.System.todayIn(localZone),
        zone: TimeZone = localZone,
    ): Boolean = DueDates.isDueToday(dateStr, today, zone)

    /**
     * The local date of a due date as a relative label: Today, Tomorrow, Yesterday, a weekday within
     * the coming week, or "Oct 6" (with the year when it is not the current year).
     */
    fun formatRelativeDate(
        dateStr: String?,
        today: LocalDate = Clock.System.todayIn(localZone),
        zone: TimeZone = localZone,
    ): String {
        val date = DueDates.localDateOf(dateStr, zone) ?: return ""
        return formatRelativeDate(date, today)
    }

    /** The same label for a local date, such as a date parsed from quick-add text. */
    fun formatRelativeDate(date: LocalDate, today: LocalDate): String = when {
        date == today -> "Today"
        date == today.plus(1, DateTimeUnit.DAY) -> "Tomorrow"
        date == today.minus(1, DateTimeUnit.DAY) -> "Yesterday"
        date > today && date < today.plus(7, DateTimeUnit.DAY) ->
            date.toJavaLocalDate().dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        date.year != today.year ->
            date.toJavaLocalDate().format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()))
        else -> date.toJavaLocalDate().format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))
    }

    /**
     * The label for a due date: the relative date, plus the time of day when the value has an
     * explicit time. Date-only values (local 23:59:59, or the legacy 00:00:00) show no time.
     * [is24Hour] is the device's 12/24 hour setting.
     */
    fun formatDueDate(
        dateStr: String?,
        today: LocalDate = Clock.System.todayIn(localZone),
        is24Hour: Boolean = false,
        zone: TimeZone = localZone,
    ): String {
        val base = formatRelativeDate(dateStr, today, zone)
        if (base.isEmpty()) return ""
        val instant = parseIsoDate(dateStr) ?: return base
        if (DueDates.isDateOnly(instant, zone)) return base
        return "$base ${formatClockTime(instant.toLocalDateTime(zone).time, is24Hour)}"
    }

    /** A time of day as "15:30" (24 hour) or "3:30 PM" (12 hour). */
    fun formatClockTime(time: LocalTime, is24Hour: Boolean): String =
        time.toJavaLocalTime().format(
            DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm a", Locale.getDefault()),
        )

    fun formatTodaySubtitle(today: LocalDate = Clock.System.todayIn(localZone)): String {
        return today.toJavaLocalDate().format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault()))
    }

    fun formatRecurrence(repeatAfter: Long, repeatMode: Int): String {
        return com.rendyhd.vicu.util.formatRecurrence(RecurrenceValue(repeatAfter, repeatMode))
    }

    fun formatFullDate(dateStr: String?): String {
        val instant = parseIsoDate(dateStr) ?: return ""
        val date = instant.toLocalDateTime(localZone).date
        return date.toJavaLocalDate().format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()))
    }

}
