package com.rendyhd.vicu.util

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

object DateUtils {
    private val isoFormatter = DateTimeFormatter.ISO_INSTANT
    private val localZone: ZoneId get() = ZoneId.systemDefault()

    fun isNullDate(dateStr: String?): Boolean {
        return dateStr.isNullOrBlank() || dateStr == Constants.NULL_DATE_STRING
    }

    fun getEndOfToday(): String {
        val endOfToday = LocalDate.now()
            .plusDays(1)
            .atStartOfDay(localZone)
            .toInstant()
        return isoFormatter.format(endOfToday)
    }

    fun nowIso(): String {
        return isoFormatter.format(Instant.now())
    }

    fun parseIsoDate(dateStr: String?): Instant? {
        if (isNullDate(dateStr)) return null
        return try {
            Instant.parse(dateStr)
        } catch (_: Exception) {
            // Handle offset formats like "2026-02-21T23:59:59+01:00"
            try {
                OffsetDateTime.parse(dateStr).toInstant()
            } catch (_: Exception) {
                null
            }
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
            val instant = OffsetDateTime.parse(dateStr).toInstant()
            isoFormatter.format(instant)
        } catch (_: Exception) {
            dateStr
        }
    }

    fun isOverdue(dateStr: String?): Boolean {
        val instant = parseIsoDate(dateStr) ?: return false
        val startOfToday = LocalDate.now()
            .atStartOfDay(localZone)
            .toInstant()
        return instant.isBefore(startOfToday)
    }

    fun isToday(dateStr: String?): Boolean {
        val instant = parseIsoDate(dateStr) ?: return false
        val today = LocalDate.now()
        val startOfToday = today.atStartOfDay(localZone).toInstant()
        val startOfTomorrow = today.plusDays(1).atStartOfDay(localZone).toInstant()
        return !instant.isBefore(startOfToday) && instant.isBefore(startOfTomorrow)
    }

    fun getDateKey(dateStr: String?): String {
        val instant = parseIsoDate(dateStr) ?: return ""
        return instant.atZone(localZone).toLocalDate().toString()
    }

    fun formatRelativeDate(dateStr: String?): String {
        val instant = parseIsoDate(dateStr) ?: return ""
        val date = instant.atZone(localZone).toLocalDate()
        val today = LocalDate.now()
        return when {
            date == today -> "Today"
            date == today.plusDays(1) -> "Tomorrow"
            date == today.minusDays(1) -> "Yesterday"
            date.isAfter(today) && date.isBefore(today.plusDays(7)) ->
                date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            else -> date.format(DateTimeFormatter.ofPattern("MMM d"))
        }
    }

    fun formatDateHeader(dateStr: String?): String {
        val instant = parseIsoDate(dateStr) ?: return ""
        val date = instant.atZone(localZone).toLocalDate()
        val today = LocalDate.now()
        return when {
            date == today -> "Today"
            date == today.plusDays(1) -> "Tomorrow"
            date.isAfter(today) && date.isBefore(today.plusDays(7)) ->
                date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
            else -> date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d"))
        }
    }

    fun formatTodaySubtitle(): String {
        val today = LocalDate.now()
        return today.format(DateTimeFormatter.ofPattern("EEEE, MMMM d"))
    }

    fun todayEndIso(): String {
        val endOfToday = LocalDate.now()
            .atTime(23, 59, 59)
            .atZone(localZone)
            .toInstant()
        return isoFormatter.format(endOfToday)
    }

    fun todayStartIso(): String {
        val startOfToday = LocalDate.now()
            .atStartOfDay(localZone)
            .toInstant()
        return isoFormatter.format(startOfToday)
    }

    fun formatRecurrence(repeatAfter: Long, repeatMode: Int): String {
        if (repeatAfter <= 0) return ""
        val days = repeatAfter / 86400
        val weeks = days / 7
        val months = days / 30
        val years = days / 365
        return when {
            years > 0 && days % 365 == 0L -> if (years == 1L) "Every year" else "Every $years years"
            months > 0 && days % 30 == 0L -> if (months == 1L) "Every month" else "Every $months months"
            weeks > 0 && days % 7 == 0L -> if (weeks == 1L) "Every week" else "Every $weeks weeks"
            days > 0 -> if (days == 1L) "Every day" else "Every $days days"
            else -> {
                val hours = repeatAfter / 3600
                if (hours > 0) "Every $hours hours" else "Every $repeatAfter seconds"
            }
        }
    }

    fun tomorrowIso(): String {
        val tomorrow = LocalDate.now()
            .plusDays(1)
            .atTime(12, 0)
            .atZone(localZone)
            .toInstant()
        return isoFormatter.format(tomorrow)
    }

    fun nextWeekIso(): String {
        val nextWeek = LocalDate.now()
            .plusWeeks(1)
            .with(java.time.DayOfWeek.MONDAY)
            .atTime(9, 0)
            .atZone(localZone)
            .toInstant()
        return isoFormatter.format(nextWeek)
    }

    fun formatFullDate(dateStr: String?): String {
        val instant = parseIsoDate(dateStr) ?: return ""
        val date = instant.atZone(localZone).toLocalDate()
        return date.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
    }

    fun formatTime(dateStr: String?): String {
        val instant = parseIsoDate(dateStr) ?: return ""
        val time = instant.atZone(localZone).toLocalTime()
        return time.format(DateTimeFormatter.ofPattern("h:mm a"))
    }
}
