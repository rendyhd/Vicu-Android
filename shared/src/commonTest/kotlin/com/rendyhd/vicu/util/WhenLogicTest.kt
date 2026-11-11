package com.rendyhd.vicu.util

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The When sheet rules; scenario A6 reads the same as E5 on the desktop (`when-logic.test.ts`). */
class WhenLogicTest {

    private val zones = listOf(TimeZone.of("Pacific/Auckland"), TimeZone.of("America/New_York"))
    private val us12 = DateDisplayFormat("en-US", hour12 = true)
    private val gb24 = DateDisplayFormat("en-GB", hour12 = false)

    // Thursday, October 8 2026.
    private val thursday = LocalDate(2026, 10, 8)
    private val nineAm = LocalTime(9, 0)

    private fun parse(
        text: String,
        now: LocalDateTime = LocalDateTime(2026, 10, 8, 10, 0),
        zone: TimeZone = zones[0],
        previous: LocalDate? = null,
    ) = WhenLogic.parseText(text, now, zone, locale = "en-US", previous = previous)

    @Test
    fun `tomorrow 9am reads as tomorrow at 09 00`() {
        val parsed = parse("tomorrow 9am")!!
        assertEquals(WhenValue(LocalDate(2026, 10, 9), nineAm), parsed.value)
        assertEquals("tomorrow 9am", parsed.matched)
    }

    @Test
    fun `next mon reads as the coming Monday - date-only - and weekend alone reads as nothing`() {
        assertEquals(WhenValue(LocalDate(2026, 10, 12), null), parse("next mon")!!.value)
        assertEquals(WhenValue(LocalDate(2026, 10, 12), null), parse("next monday")!!.value)
        // The weekend is a quick choice of the sheet, not something the text parser reads.
        assertNull(parse("weekend"))
        assertEquals(WhenValue(LocalDate(2026, 10, 10), LocalTime(15, 0)), parse("saturday 3pm")!!.value)
    }

    @Test
    fun `this weekend is words - not a date - the parser spec - so only the quick choice sets it`() {
        assertNull(parse("this weekend"))
        assertNull(parse("plan the weekend"))
        assertEquals(LocalDate(2026, 10, 10), WhenLogic.quickChoices(thursday).first { it.id == WhenQuickChoice.Id.WEEKEND }.date)
    }

    @Test
    fun `a day without a time reads as date-only`() {
        assertEquals(WhenValue(LocalDate(2026, 10, 9), null), parse("tomorrow")!!.value)
        assertEquals(WhenValue(LocalDate(2026, 10, 20), LocalTime(15, 0)), parse("oct 20 at 3pm")!!.value)
    }

    @Test
    fun `text without a date and blank text read as nothing`() {
        assertNull(parse("no idea what this means"))
        assertNull(parse(""))
        assertNull(parse("   "))
    }

    @Test
    fun `the sheet's own text reads back as its value - in both clocks`() {
        val yesterday = LocalDate(2026, 10, 7)
        val days = listOf(yesterday, thursday, LocalDate(2026, 10, 9), LocalDate(2026, 10, 10), LocalDate(2026, 11, 20))
        for (fmt in listOf(us12, gb24, DateDisplayFormat("en-GB", hour12 = true))) {
            for (date in days) {
                for (time in listOf(LocalTime(13, 0), LocalTime(13, 5), null)) {
                    val value = WhenValue(date, time)
                    val text = WhenLogic.text(value, thursday, fmt)
                    assertEquals(value, WhenLogic.parseText(text, LocalDateTime(2026, 10, 8, 10, 0), zones[0], fmt.localeTag, previous = date)?.value, text)
                }
            }
        }
    }

    @Test
    fun `issue 98 - editing the time of today's or tomorrow's text keeps the time`() {
        // The sheet showed "Today, Oct 8, 1:00 PM"; the minutes were typed over.
        assertEquals(WhenValue(thursday, LocalTime(13, 5)), parse("Today, Oct 8, 1:05 PM", previous = thursday)!!.value)
        assertEquals(WhenValue(LocalDate(2026, 10, 9), LocalTime(13, 5)), parse("Tomorrow, Oct 9, 1:05 p.m.", previous = LocalDate(2026, 10, 9))!!.value)
        assertEquals(WhenValue(thursday, LocalTime(13, 5)), parse("today, 8 oct, 13:05", previous = thursday)!!.value)
        // Without a time after the date it is date-only, as the sheet writes it.
        assertEquals(WhenValue(thursday, null), parse("Today, Oct 8", previous = thursday)!!.value)
    }

    @Test
    fun `in the day word text the part that was changed decides the day`() {
        // The date after the word was changed: it wins.
        assertEquals(WhenValue(LocalDate(2026, 10, 12), LocalTime(13, 0)), parse("Tomorrow, Oct 12, 1:00 PM", previous = LocalDate(2026, 10, 9))!!.value)
        // The word was changed: it wins, and the time stays.
        assertEquals(WhenValue(LocalDate(2026, 10, 9), LocalTime(13, 0)), parse("Tomorrow, Oct 8, 1:00 PM", previous = thursday)!!.value)
        // Nothing to compare with: the word leads, as in the parser.
        assertEquals(WhenValue(thursday, LocalTime(13, 0)), parse("Today, Oct 12, 1:00 PM")!!.value)
        // Yesterday's date stays in this year.
        assertEquals(WhenValue(LocalDate(2026, 10, 7), LocalTime(9, 30)), parse("Yesterday, Oct 7, 9:30 AM", previous = LocalDate(2026, 10, 7))!!.value)
    }

    @Test
    fun `dotted am and pm read as on the desktop`() {
        assertEquals(WhenValue(thursday, LocalTime(13, 5)), parse("1:05 p.m.")!!.value)
        assertEquals(WhenValue(LocalDate(2026, 10, 9), LocalTime(15, 0)), parse("tomorrow 3 p.m.")!!.value)
        assertEquals(WhenValue(LocalDate(2026, 10, 9), LocalTime(9, 0)), parse("tomorrow at 9 a.m")!!.value)
        assertEquals(WhenValue(LocalDate(2026, 10, 20), LocalTime(15, 0)), parse("oct 20 at 3 P.M.")!!.value)
    }

    @Test
    fun `a date only phrase is judged against the day - not the clock`() {
        // Thursday evening: "thursday" is today, not next week.
        val evening = LocalDateTime(2026, 10, 8, 22, 30)
        assertEquals(thursday, parse("thursday", evening)!!.value.date)
    }

    @Test
    fun `a day without a time is stored as local 23 59 59 in every zone`() {
        for (zone in zones) {
            val due = WhenLogic.dueOf(WhenValue(LocalDate(2026, 10, 9), null), zone)!!
            assertEquals(LocalDateTime(2026, 10, 9, 23, 59, 59), due.toLocalDateTime(zone), zone.id)
            assertEquals(DueDates.dateOnlyDue(LocalDate(2026, 10, 9), zone), due, zone.id)
        }
    }

    @Test
    fun `a day with a time is that minute in the zone`() {
        for (zone in zones) {
            val due = WhenLogic.dueOf(WhenValue(LocalDate(2026, 10, 9), nineAm), zone)!!
            assertEquals(LocalDateTime(2026, 10, 9, 9, 0), due.toLocalDateTime(zone), zone.id)
            assertEquals(LocalDateTime(2026, 10, 9, 9, 0).toInstant(zone), due, zone.id)
        }
    }

    @Test
    fun `no day means clear the due date`() {
        assertNull(WhenLogic.dueOf(WhenValue.EMPTY, zones[0]))
        assertNull(WhenLogic.dueOf(WhenValue(null, nineAm), zones[0]))
    }

    @Test
    fun `a stored due date reads back as the value it was made from`() {
        for (zone in zones) {
            for (value in listOf(
                WhenValue(LocalDate(2026, 10, 9), null),
                WhenValue(LocalDate(2026, 10, 9), nineAm),
                WhenValue(LocalDate(2026, 12, 31), LocalTime(18, 0)),
            )) {
                val due = WhenLogic.dueOf(value, zone)!!.toString()
                assertEquals(value, WhenLogic.valueOfDue(due, zone), "${zone.id} $value")
            }
        }
    }

    @Test
    fun `a legacy midnight due date reads as date-only and the null date as nothing`() {
        val zone = zones[1]
        val midnight = LocalDateTime(2026, 10, 9, 0, 0).toInstant(zone).toString()
        assertEquals(WhenValue(LocalDate(2026, 10, 9), null), WhenLogic.valueOfDue(midnight, zone))
        for (none in listOf(null, "", Constants.NULL_DATE_STRING, "not a date")) {
            assertEquals(WhenValue.EMPTY, WhenLogic.valueOfDue(none, zone), "'$none'")
        }
    }

    @Test
    fun `picking a day keeps the time and a time without a day starts from today`() {
        val picked = WhenLogic.withDate(WhenValue(LocalDate(2026, 10, 9), nineAm), LocalDate(2026, 10, 20))
        assertEquals(WhenValue(LocalDate(2026, 10, 20), nineAm), picked)
        assertEquals(WhenValue(thursday, LocalTime(15, 0)), WhenLogic.withTime(WhenValue.EMPTY, LocalTime(15, 0), thursday))
        assertEquals(WhenValue(LocalDate(2026, 10, 20), null), WhenLogic.withTime(picked, null, thursday))
    }

    @Test
    fun `quick choices land on today - tomorrow - the coming Saturday and the coming Monday`() {
        val choices = WhenLogic.quickChoices(thursday).associate { it.id to it.date }
        assertEquals(thursday, choices[WhenQuickChoice.Id.TODAY])
        assertEquals(LocalDate(2026, 10, 9), choices[WhenQuickChoice.Id.TOMORROW])
        assertEquals(LocalDate(2026, 10, 10), choices[WhenQuickChoice.Id.WEEKEND])
        assertEquals(LocalDate(2026, 10, 12), choices[WhenQuickChoice.Id.NEXT_WEEK])
        assertEquals(listOf("Today", "Tomorrow", "This weekend", "Next week"), WhenLogic.quickChoices(thursday).map { it.label })
    }

    @Test
    fun `the weekend and next week move on from a Saturday - a Sunday and a Monday`() {
        fun on(day: Int): Map<WhenQuickChoice.Id, LocalDate> =
            WhenLogic.quickChoices(LocalDate(2026, 10, day)).associate { it.id to it.date }
        // Saturday 10: the next Saturday, the coming Monday.
        assertEquals(LocalDate(2026, 10, 17), on(10)[WhenQuickChoice.Id.WEEKEND])
        assertEquals(LocalDate(2026, 10, 12), on(10)[WhenQuickChoice.Id.NEXT_WEEK])
        // Sunday 11: the Saturday after, tomorrow is Monday.
        assertEquals(LocalDate(2026, 10, 17), on(11)[WhenQuickChoice.Id.WEEKEND])
        assertEquals(LocalDate(2026, 10, 12), on(11)[WhenQuickChoice.Id.NEXT_WEEK])
        // Monday 12: this week's Saturday; next week is a week away.
        assertEquals(LocalDate(2026, 10, 17), on(12)[WhenQuickChoice.Id.WEEKEND])
        assertEquals(LocalDate(2026, 10, 19), on(12)[WhenQuickChoice.Id.NEXT_WEEK])
        // Friday 9: tomorrow is the weekend.
        assertEquals(LocalDate(2026, 10, 10), on(9)[WhenQuickChoice.Id.WEEKEND])
    }

    @Test
    fun `next week matches the due-date policy`() {
        for (zone in zones) {
            val next = WhenLogic.quickChoices(thursday).first { it.id == WhenQuickChoice.Id.NEXT_WEEK }
            assertEquals(DueDates.nextWeek(thursday, zone), WhenLogic.dueOf(WhenValue(next.date, null), zone))
        }
    }

    @Test
    fun `hints name the weekday - and next week names the day of the month`() {
        val choices = WhenLogic.quickChoices(thursday).associateBy { it.id }
        assertEquals("Thu", WhenLogic.hint(choices.getValue(WhenQuickChoice.Id.TODAY), us12))
        assertEquals("Fri", WhenLogic.hint(choices.getValue(WhenQuickChoice.Id.TOMORROW), us12))
        assertEquals("Sat", WhenLogic.hint(choices.getValue(WhenQuickChoice.Id.WEEKEND), us12))
        assertEquals("Mon 12", WhenLogic.hint(choices.getValue(WhenQuickChoice.Id.NEXT_WEEK), us12))
    }

    @Test
    fun `value text uses the chip phrasing and the clock setting`() {
        val saturdayAt3 = WhenValue(LocalDate(2026, 10, 10), LocalTime(15, 0))
        assertEquals("Sat, Oct 10, 3:00 PM", WhenLogic.text(saturdayAt3, thursday, us12))
        assertEquals("Sat 10 Oct, 15:00", WhenLogic.text(saturdayAt3, thursday, gb24))
        assertEquals("Tomorrow, Oct 9", WhenLogic.text(WhenValue(LocalDate(2026, 10, 9), null), thursday, us12))
        assertEquals("", WhenLogic.text(WhenValue.EMPTY, thursday, us12))
    }

    @Test
    fun `time labels follow the clock setting`() {
        assertEquals("9:00 AM", WhenLogic.timeLabel(nineAm, us12))
        assertEquals("6:00 PM", WhenLogic.timeLabel(LocalTime(18, 0), us12))
        assertEquals("09:00", WhenLogic.timeLabel(nineAm, gb24))
        assertEquals(listOf("09:00", "12:00", "15:00", "18:00"), WhenLogic.TIME_CHOICES.map { WhenLogic.timeLabel(it, gb24) })
    }

    // The month grid: the same cases as the desktop `monthGrid` tests.

    @Test
    fun `the month grid has six Monday-first weeks around the month`() {
        val grid = WhenLogic.monthGrid(LocalDate(2026, 10, 15))
        assertEquals(6, grid.size)
        assertEquals(List(6) { 7 }, grid.map { it.size })
        // 1 October 2026 is a Thursday: the first row starts on Monday 28 September.
        assertEquals(GridDay(LocalDate(2026, 9, 28), inMonth = false), grid[0][0])
        assertEquals(GridDay(LocalDate(2026, 10, 1), inMonth = true), grid[0][3])
        assertEquals(LocalDate(2026, 11, 8), grid[5][6].date)
        assertEquals(31, grid.flatten().count { it.inMonth })
        // Every row runs Monday to Sunday.
        assertEquals(List(6) { kotlinx.datetime.DayOfWeek.MONDAY }, grid.map { it.first().date.dayOfWeek })
        assertEquals(List(6) { kotlinx.datetime.DayOfWeek.SUNDAY }, grid.map { it.last().date.dayOfWeek })
    }

    @Test
    fun `the month grid handles a month that starts on Monday and February in a leap year`() {
        assertEquals(GridDay(LocalDate(2026, 6, 1), inMonth = true), WhenLogic.monthGrid(LocalDate(2026, 6, 10))[0][0])
        assertEquals(29, WhenLogic.monthGrid(LocalDate(2028, 2, 1)).flatten().count { it.inMonth })
    }

    @Test
    fun `months move by whole months and clamp the day`() {
        assertEquals(LocalDate(2026, 2, 28), WhenLogic.addMonths(LocalDate(2026, 1, 31), 1))
        assertEquals(LocalDate(2028, 2, 29), WhenLogic.addMonths(LocalDate(2028, 1, 31), 1))
        assertEquals(LocalDate(2025, 12, 15), WhenLogic.addMonths(LocalDate(2026, 10, 15), -10))
        assertEquals(LocalDate(2027, 1, 5), WhenLogic.addMonths(LocalDate(2026, 12, 5), 1))
        assertEquals(LocalDate(2026, 10, 1), WhenLogic.monthStart(LocalDate(2026, 10, 15)))
    }
}
