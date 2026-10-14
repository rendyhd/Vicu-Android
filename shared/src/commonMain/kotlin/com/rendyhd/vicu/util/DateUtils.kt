package com.rendyhd.vicu.util

import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import kotlinx.datetime.atStartOfDayIn
import kotlin.time.Duration

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

    fun formatRecurrence(repeatAfter: Long, repeatMode: Int): String {
        return com.rendyhd.vicu.util.formatRecurrence(RecurrenceValue(repeatAfter, repeatMode))
    }
}
