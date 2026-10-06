package com.rendyhd.vicu.util

import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
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

    fun getEndOfToday(): String = endOfDayIso(Clock.System.todayIn(localZone), localZone)

    /** The first instant of the day after [date] in [zone]: the exclusive end of [date]. */
    fun endOfDayIso(date: LocalDate, zone: TimeZone): String =
        date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toString()

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

    /** [today] defaults to the system clock; pass the day from DayClock so labels follow midnight. */
    fun isOverdue(dateStr: String?, today: LocalDate = Clock.System.todayIn(localZone)): Boolean {
        val instant = parseIsoDate(dateStr) ?: return false
        return instant < today.atStartOfDayIn(localZone)
    }

    fun isToday(dateStr: String?, today: LocalDate = Clock.System.todayIn(localZone)): Boolean {
        val instant = parseIsoDate(dateStr) ?: return false
        val date = instant.toLocalDateTime(localZone).date
        return date == today
    }

    fun getDateKey(dateStr: String?): String {
        val instant = parseIsoDate(dateStr) ?: return ""
        return instant.toLocalDateTime(localZone).date.toString()
    }

    fun formatRelativeDate(dateStr: String?, today: LocalDate = Clock.System.todayIn(localZone)): String {
        val instant = parseIsoDate(dateStr) ?: return ""
        val date = instant.toLocalDateTime(localZone).date
        return when {
            date == today -> "Today"
            date == today.plus(1, DateTimeUnit.DAY) -> "Tomorrow"
            date == today.minus(1, DateTimeUnit.DAY) -> "Yesterday"
            date > today && date < today.plus(7, DateTimeUnit.DAY) ->
                date.toJavaLocalDate().dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            else -> date.toJavaLocalDate().format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))
        }
    }

    fun formatDateHeader(dateStr: String?): String {
        val instant = parseIsoDate(dateStr) ?: return ""
        val date = instant.toLocalDateTime(localZone).date
        val today = Clock.System.todayIn(localZone)
        return when {
            date == today -> "Today"
            date == today.plus(1, DateTimeUnit.DAY) -> "Tomorrow"
            date > today && date < today.plus(7, DateTimeUnit.DAY) ->
                date.toJavaLocalDate().dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
            else -> date.toJavaLocalDate().format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault()))
        }
    }

    fun formatTodaySubtitle(today: LocalDate = Clock.System.todayIn(localZone)): String {
        return today.toJavaLocalDate().format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault()))
    }

    fun todayEndIso(): String {
        val today = Clock.System.todayIn(localZone)
        val endOfToday = LocalDateTime(today.year, today.monthNumber, today.dayOfMonth, 23, 59, 59)
            .toInstant(localZone)
        return endOfToday.toString()
    }

    fun todayStartIso(): String {
        val today = Clock.System.todayIn(localZone)
        val startOfToday = today.atStartOfDayIn(localZone)
        return startOfToday.toString()
    }

    fun formatRecurrence(repeatAfter: Long, repeatMode: Int): String {
        return com.rendyhd.vicu.util.formatRecurrence(RecurrenceValue(repeatAfter, repeatMode))
    }

    fun tomorrowIso(): String {
        val tomorrow = Clock.System.todayIn(localZone).plus(1, DateTimeUnit.DAY)
        val tomorrowNoon = LocalDateTime(tomorrow.year, tomorrow.monthNumber, tomorrow.dayOfMonth, 12, 0)
            .toInstant(localZone)
        return tomorrowNoon.toString()
    }

    fun nextWeekIso(): String {
        val today = Clock.System.todayIn(localZone)
        val daysUntilNextMonday = 8 - today.dayOfWeek.value
        val nextMonday = today.plus(daysUntilNextMonday, DateTimeUnit.DAY)
        val nextMondayNineAM = LocalDateTime(nextMonday.year, nextMonday.monthNumber, nextMonday.dayOfMonth, 9, 0)
            .toInstant(localZone)
        return nextMondayNineAM.toString()
    }

    fun formatFullDate(dateStr: String?): String {
        val instant = parseIsoDate(dateStr) ?: return ""
        val date = instant.toLocalDateTime(localZone).date
        return date.toJavaLocalDate().format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()))
    }

    fun formatTime(dateStr: String?): String {
        val instant = parseIsoDate(dateStr) ?: return ""
        val time = instant.toLocalDateTime(localZone).time
        return time.toJavaLocalTime().format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
    }
}
