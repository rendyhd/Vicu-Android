package com.rendyhd.vicu.util

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.Month
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/** What the UI needs to phrase a date: the region locale and the clock. */
data class DateDisplayFormat(
    val locale: Locale,
    /** True for the 12-hour clock ("3:00 PM"), false for the 24-hour clock ("15:00"). */
    val hour12: Boolean,
) {
    companion object {
        /** The device locale with the device's 12/24 hour setting ([is24Hour]). */
        fun system(is24Hour: Boolean): DateDisplayFormat = DateDisplayFormat(Locale.getDefault(), hour12 = !is24Hour)
    }
}

/** Where a date is shown; each context has its own rule in section 8.3 of the contract. */
enum class DateContext {
    ROW,
    ROW_IN_TODAY,
    ROW_IN_DAY_GROUP,
    CHIP,
    HEADER_DAY,
    HEADER_FULL,
    LOGBOOK_GROUP,
    LOGBOOK_TIME,
}

/**
 * How a due date or a completion time is phrased (docs/cross-app-semantics-v1.md section 8,
 * pinned by the `dateDisplay` vectors of test-fixtures/cross-app-semantics-v1.json). The same rules
 * as the desktop `src/shared/date-display.ts`.
 *
 * Pure: the locale, the clock setting and the current day are passed in. For English the text is
 * built from the names and patterns below (never from platform locale data); other locales keep
 * the phrase structure with the platform's names, month/day order and AM/PM markers.
 */
object DateDisplay {

    private class Pattern(
        val dayMonth: String,
        val dayMonthYear: String,
        val weekdayDayMonth: String,
        val weekdayDayMonthYear: String,
        val weekdayDay: String,
        val fullDate: String,
        val fullDateYear: String,
        val monthYear: String,
        val time12: String,
        val time24: String,
    )

    private class Names(
        val weekdaysShort: List<String>,
        val weekdaysLong: List<String>,
        val monthsShort: List<String>,
        val monthsLong: List<String>,
        val am: String,
        val pm: String,
    )

    private val ENGLISH_NAMES_BASE = Names(
        weekdaysShort = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"),
        weekdaysLong = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"),
        monthsShort = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"),
        monthsLong = listOf(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December",
        ),
        am = "",
        pm = "",
    )

    private fun englishNames(am: String, pm: String) = Names(
        ENGLISH_NAMES_BASE.weekdaysShort,
        ENGLISH_NAMES_BASE.weekdaysLong,
        ENGLISH_NAMES_BASE.monthsShort,
        ENGLISH_NAMES_BASE.monthsLong,
        am,
        pm,
    )

    private val US_NAMES = englishNames("AM", "PM")
    private val GB_NAMES = englishNames("am", "pm")

    private const val TIME12 = "{h}:{mm} {AMPM}"
    private const val TIME24 = "{HH}:{mm}"

    private val US = Pattern(
        dayMonth = "{MMM} {d}",
        dayMonthYear = "{MMM} {d}, {yyyy}",
        weekdayDayMonth = "{EEE}, {MMM} {d}",
        weekdayDayMonthYear = "{EEE}, {MMM} {d}, {yyyy}",
        weekdayDay = "{EEE} {d}",
        fullDate = "{EEEE}, {MMMM} {d}",
        fullDateYear = "{EEEE}, {MMMM} {d}, {yyyy}",
        monthYear = "{MMMM} {yyyy}",
        time12 = TIME12,
        time24 = TIME24,
    )

    private val GB = Pattern(
        dayMonth = "{d} {MMM}",
        dayMonthYear = "{d} {MMM} {yyyy}",
        weekdayDayMonth = "{EEE} {d} {MMM}",
        weekdayDayMonthYear = "{EEE} {d} {MMM} {yyyy}",
        weekdayDay = "{EEE} {d}",
        fullDate = "{EEEE} {d} {MMMM}",
        fullDateYear = "{EEEE} {d} {MMMM} {yyyy}",
        monthYear = "{MMMM} {yyyy}",
        time12 = TIME12,
        time24 = TIME24,
    )

    /** English regions that write the month first; every other English locale follows en-GB. */
    private val MONTH_FIRST_REGIONS = setOf("US", "CA", "PH")

    private const val TODAY = "Today"
    private const val YESTERDAY = "Yesterday"
    private const val TOMORROW = "Tomorrow"
    private fun daysAgo(n: Int) = "$n days ago"

    /** Patterns and names for one locale. */
    private class Phrases(val pattern: Pattern, val names: Names)

    private fun phrasesFor(locale: Locale): Phrases {
        if (locale.language.equals("en", ignoreCase = true)) {
            val region = locale.country.uppercase()
            return if (region.isEmpty() || region in MONTH_FIRST_REGIONS) Phrases(US, US_NAMES) else Phrases(GB, GB_NAMES)
        }
        val shortPattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern(
            FormatStyle.SHORT, null, IsoChronology.INSTANCE, locale,
        )
        val monthFirst = shortPattern.indexOf('M').let { m -> m >= 0 && m < shortPattern.indexOf('d') }
        val marker = DateTimeFormatter.ofPattern("a", locale)
        return Phrases(
            pattern = if (monthFirst) US else GB,
            names = Names(
                weekdaysShort = DayOfWeek.entries.map { it.getDisplayName(TextStyle.SHORT, locale) },
                weekdaysLong = DayOfWeek.entries.map { it.getDisplayName(TextStyle.FULL, locale) },
                monthsShort = Month.entries.map { it.getDisplayName(TextStyle.SHORT, locale) },
                monthsLong = Month.entries.map { it.getDisplayName(TextStyle.FULL, locale) },
                am = marker.format(LocalTime.of(1, 0)),
                pm = marker.format(LocalTime.of(13, 0)),
            ),
        )
    }

    private fun pad2(n: Int) = n.toString().padStart(2, '0')

    private fun fill(template: String, value: LocalDateTime, names: Names): String {
        val hour = value.hour
        return template
            .replace("{EEEE}", names.weekdaysLong[value.dayOfWeek.ordinal])
            .replace("{EEE}", names.weekdaysShort[value.dayOfWeek.ordinal])
            .replace("{MMMM}", names.monthsLong[value.monthNumber - 1])
            .replace("{MMM}", names.monthsShort[value.monthNumber - 1])
            .replace("{yyyy}", value.year.toString())
            .replace("{d}", value.dayOfMonth.toString())
            .replace("{HH}", pad2(hour))
            .replace("{h}", (if (hour % 12 == 0) 12 else hour % 12).toString())
            .replace("{mm}", pad2(value.minute))
            .replace("{AMPM}", if (hour < 12) names.am else names.pm)
    }

    private fun Phrases.dayMonth(value: LocalDateTime, withYear: Boolean) =
        fill(if (withYear) pattern.dayMonthYear else pattern.dayMonth, value, names)

    private fun Phrases.weekdayDayMonth(value: LocalDateTime, withYear: Boolean) =
        fill(if (withYear) pattern.weekdayDayMonthYear else pattern.weekdayDayMonth, value, names)

    private fun Phrases.weekdayDay(value: LocalDateTime) = fill(pattern.weekdayDay, value, names)

    private fun Phrases.weekdayShort(value: LocalDateTime) = fill("{EEE}", value, names)

    private fun Phrases.fullDate(value: LocalDateTime, withYear: Boolean) =
        fill(if (withYear) pattern.fullDateYear else pattern.fullDate, value, names)

    private fun Phrases.monthYear(value: LocalDateTime) = fill(pattern.monthYear, value, names)

    private fun Phrases.time(value: LocalDateTime, hour12: Boolean) =
        fill(if (hour12) pattern.time12 else pattern.time24, value, names)

    /** A day phrase: the short date, with the year only when it differs from the current year. */
    private fun Phrases.shortDate(value: LocalDateTime, today: LocalDate) =
        dayMonth(value, value.year != today.year)

    private fun Phrases.weekdayDate(value: LocalDateTime, today: LocalDate) =
        weekdayDayMonth(value, value.year != today.year)

    private fun Phrases.withTime(day: String, value: LocalDateTime, dateOnly: Boolean, fmt: DateDisplayFormat) =
        if (dateOnly) day else "$day, ${time(value, fmt.hour12)}"

    private fun Phrases.rowDay(diff: Int, value: LocalDateTime, today: LocalDate): String = when {
        diff <= -7 -> shortDate(value, today)
        diff <= -2 -> daysAgo(-diff)
        diff == -1 -> YESTERDAY
        diff == 0 -> TODAY
        diff == 1 -> TOMORROW
        diff <= 6 -> weekdayShort(value)
        else -> shortDate(value, today)
    }

    /**
     * The text of a date for one context (section 8.3 of the contract). [value] is a local
     * wall-clock time and [today] the local date. [dateOnly] says the value has no time of day
     * (section 1.2); the logbook contexts ignore it, their value is always a completion time. An
     * empty string means there is nothing to show.
     */
    fun format(
        context: DateContext,
        value: LocalDateTime,
        today: LocalDate,
        dateOnly: Boolean,
        fmt: DateDisplayFormat,
    ): String {
        val p = phrasesFor(fmt.locale)
        val diff = today.daysUntil(value.date)
        return when (context) {
            DateContext.ROW -> p.withTime(p.rowDay(diff, value, today), value, dateOnly, fmt)
            DateContext.ROW_IN_TODAY ->
                if (diff == 0) {
                    if (dateOnly) "" else p.time(value, fmt.hour12)
                } else {
                    p.withTime(p.rowDay(diff, value, today), value, dateOnly, fmt)
                }
            DateContext.ROW_IN_DAY_GROUP -> if (dateOnly) "" else p.time(value, fmt.hour12)
            DateContext.CHIP -> p.withTime(p.weekdayDate(value, today), value, dateOnly, fmt)
            DateContext.HEADER_DAY -> when {
                diff == 0 -> TODAY
                diff == 1 -> TOMORROW
                diff in 2..6 -> p.weekdayDay(value)
                else -> p.weekdayDate(value, today)
            }
            DateContext.HEADER_FULL -> p.fullDate(value, value.year != today.year)
            // A completion a little in the future (a clock that is ahead) counts as today.
            DateContext.LOGBOOK_GROUP -> when {
                diff >= 0 -> TODAY
                diff == -1 -> YESTERDAY
                diff >= -6 -> p.weekdayDate(value, today)
                else -> p.monthYear(value)
            }
            DateContext.LOGBOOK_TIME -> p.time(value, fmt.hour12)
        }
    }

    /** [format] for a whole day, such as an Upcoming day header or the subtitle of Today. */
    fun formatDay(context: DateContext, date: LocalDate, today: LocalDate, fmt: DateDisplayFormat): String =
        format(context, LocalDateTime(date.year, date.monthNumber, date.dayOfMonth, 0, 0), today, true, fmt)

    /**
     * [format] for a stored due date or completion time (an ISO instant string). The value is read
     * in [zone] and counts as date only per section 1.2. Empty for no date.
     */
    fun formatDue(
        context: DateContext,
        dueDate: String?,
        today: LocalDate,
        zone: TimeZone,
        fmt: DateDisplayFormat,
    ): String {
        val instant = DateUtils.parseIsoDate(dueDate) ?: return ""
        return format(context, instant.toLocalDateTime(zone), today, DueDates.isDateOnly(instant, zone), fmt)
    }

    /** The short date with its year of a stored instant, read in [zone]; empty for no date. */
    fun formatDayMonthYear(isoDate: String?, zone: TimeZone, fmt: DateDisplayFormat): String {
        val instant = DateUtils.parseIsoDate(isoDate) ?: return ""
        return formatDayMonthYear(DueDates.localDateOf(instant, zone), fmt)
    }

    /** The short date with its year ("Mar 1, 2026", "1 Mar 2026"), for details such as Created. */
    fun formatDayMonthYear(date: LocalDate, fmt: DateDisplayFormat): String {
        val p = phrasesFor(fmt.locale)
        return p.dayMonth(LocalDateTime(date.year, date.monthNumber, date.dayOfMonth, 0, 0), true)
    }

    /** The year, the short date and the time ("Jan 1, 2030, 9:00 AM", "1 Jan 2030, 09:00"); nothing relative. */
    fun formatAbsolute(value: LocalDateTime, fmt: DateDisplayFormat): String {
        val p = phrasesFor(fmt.locale)
        return "${p.dayMonth(value, true)}, ${p.time(value, fmt.hour12)}"
    }
}
