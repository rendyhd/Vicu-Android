package com.rendyhd.vicu.util

import com.rendyhd.vicu.util.parser.DateOptions
import com.rendyhd.vicu.util.parser.deviceLocaleTag
import com.rendyhd.vicu.util.parser.extractDate
import com.rendyhd.vicu.util.parser.isDayFirstLocale
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/** A picked day and, optionally, a time of day. No time means date-only. */
data class WhenValue(val date: LocalDate?, val time: LocalTime?) {
    companion object {
        val EMPTY = WhenValue(null, null)
    }
}

/** One of the quick choices of the When sheet. [date] is the day the choice lands on. */
data class WhenQuickChoice(val id: Id, val label: String, val date: LocalDate) {
    enum class Id { TODAY, TOMORROW, WEEKEND, NEXT_WEEK }
}

/** What the quick-add parser made of typed text: the value and the part of the text it read. */
data class WhenParse(val value: WhenValue, val matched: String)

/**
 * The rules of the When sheet, the same as the desktop When panel (`src/renderer/lib/when-logic.ts`,
 * scenario E5 / A6 of the design review): what a picked day and time mean as a due date, the quick
 * choices, and how typed text becomes a selection through the quick-add parser's own date
 * extraction, so the sheet and the quick-add field read "tomorrow 9am" the same way. Pure: the
 * zone, the day and the clock are passed in (see [DueDates] for the due-date policy it uses).
 */
object WhenLogic {

    /** The times offered under the calendar. */
    val TIME_CHOICES: List<LocalTime> = listOf(LocalTime(9, 0), LocalTime(12, 0), LocalTime(15, 0), LocalTime(18, 0))

    /**
     * What a stored due date reads as in the sheet. No date, the null date or garbage: nothing
     * picked. A date-only value (local 23:59:59, or the legacy 00:00) has a day and no time.
     */
    fun valueOfDue(due: String?, zone: TimeZone): WhenValue {
        val instant = DateUtils.parseIsoDate(due) ?: return WhenValue.EMPTY
        val local = instant.toLocalDateTime(zone)
        if (DueDates.isDateOnly(instant, zone)) return WhenValue(local.date, null)
        return WhenValue(local.date, LocalTime(local.hour, local.minute))
    }

    /**
     * The due date a value stands for: a day without a time is local 23:59:59 ([DueDates]), a day
     * with a time is that minute. Null when no day is picked (clear the due date).
     */
    fun dueOf(value: WhenValue, zone: TimeZone): Instant? {
        val date = value.date ?: return null
        val time = value.time ?: return DueDates.dateOnlyDue(date, zone)
        return LocalDateTime(date, time).toInstant(zone)
    }

    /** The same value with a day (and the time kept). */
    fun withDate(value: WhenValue, date: LocalDate): WhenValue = WhenValue(date, value.time)

    /** The same value with a time; a time without a day picked starts from [today]. A null time makes it date-only. */
    fun withTime(value: WhenValue, time: LocalTime?, today: LocalDate): WhenValue =
        if (time == null) WhenValue(value.date, null) else WhenValue(value.date ?: today, time)

    /** The Saturday after [today] (a Saturday gives the next one). */
    fun comingSaturday(today: LocalDate): LocalDate {
        val ahead = (6 - today.dayOfWeek.isoDayNumber + 7) % 7
        return today.plus(if (ahead == 0) 7 else ahead, DateTimeUnit.DAY)
    }

    /** Today, Tomorrow, This weekend (the coming Saturday) and Next week (the coming Monday), from [today]. */
    fun quickChoices(today: LocalDate): List<WhenQuickChoice> = listOf(
        WhenQuickChoice(WhenQuickChoice.Id.TODAY, "Today", today),
        WhenQuickChoice(WhenQuickChoice.Id.TOMORROW, "Tomorrow", today.plus(1, DateTimeUnit.DAY)),
        WhenQuickChoice(WhenQuickChoice.Id.WEEKEND, "This weekend", comingSaturday(today)),
        WhenQuickChoice(WhenQuickChoice.Id.NEXT_WEEK, "Next week", DueDates.nextWeekStart(today)),
    )

    /** The weekday hint under a quick choice: "Wed", and for next week "Mon 12". */
    fun hint(choice: WhenQuickChoice, fmt: DateDisplayFormat): String = when (choice.id) {
        WhenQuickChoice.Id.NEXT_WEEK -> DateDisplay.formatWeekdayDay(choice.date, fmt)
        else -> DateDisplay.formatWeekdayShort(choice.date, fmt)
    }

    /**
     * What the quick-add parser's date extraction makes of typed text ("tomorrow 9am", "next mon",
     * "oct 20 at 3pm"). A phrase that names no time gives a date-only value. Null when the text has
     * no date in it. [locale] (a BCP 47 tag) orders slash dates; the device locale when null.
     */
    fun parseText(text: String, now: LocalDateTime, zone: TimeZone, locale: String? = null): WhenParse? {
        if (text.isBlank()) return null
        val options = DateOptions(
            reference = now,
            zone = zone,
            dayFirst = isDayFirstLocale(locale ?: deviceLocaleTag()),
        )
        val found = extractDate(text, mutableListOf(), options)
        val due = found.dueDate ?: return null
        val time = if (found.hasTime) LocalTime(due.hour, due.minute) else null
        return WhenParse(WhenValue(due.date, time), found.tokens.firstOrNull()?.raw ?: text.trim())
    }

    /** A value as short text, the chip phrasing ("Sat, Oct 10, 3:00 PM"). Empty when no day is picked. */
    fun text(value: WhenValue, today: LocalDate, fmt: DateDisplayFormat): String {
        val date = value.date ?: return ""
        val at = LocalDateTime(date, value.time ?: DueDates.DATE_ONLY_TIME)
        return DateDisplay.format(DateContext.CHIP, at, today, value.time == null, fmt)
    }

    /** The clock text of a time chip. */
    fun timeLabel(time: LocalTime, fmt: DateDisplayFormat): String = DateDisplay.formatTime(time, fmt)
}
