package com.rendyhd.vicu.util.parser

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Date and recurrence rules beyond the corpus (docs/cross-app-semantics-v1.md, section 5): other
 * reference times (an evening, a Sunday, a day the clocks change), locales, and the edge handling
 * the corpus does not reach. The corpus itself runs in [NlpCorpusTest].
 */
class TaskParserDatesTest {

    private val amsterdam = TimeZone.of("Europe/Amsterdam")
    private val todoist = ParserConfig(syntaxMode = SyntaxMode.TODOIST, locale = "en-US")
    private val vikunja = ParserConfig(syntaxMode = SyntaxMode.VIKUNJA, locale = "en-US")

    private fun parseAt(
        input: String,
        now: String = TUESDAY_MORNING,
        config: ParserConfig = todoist,
        zone: TimeZone = amsterdam,
    ): ParseResult = TaskParser.parse(input, config, LocalDateTime.parse(now), zone)

    private fun dueOf(result: ParseResult): String? = result.dueDate?.let(::wallOf)

    private fun dueAt(input: String, now: String = TUESDAY_MORNING, config: ParserConfig = todoist): String? =
        dueOf(parseAt(input, now, config))

    // --- A date-only phrase is judged against the day, not the clock ---------------------------

    @Test
    fun `tuesday typed on a Tuesday evening stays today`() {
        val r = parseAt("Standup tuesday", TUESDAY_EVENING)
        assertEquals("Standup", r.title)
        assertEquals("2026-10-06T23:59:59", dueOf(r))
        assertFalse(r.dueDateHasTime)
    }

    @Test
    fun `a date named for today stays today late in the evening`() {
        assertEquals("2026-10-06T23:59:59", dueAt("Plan oct 6", TUESDAY_EVENING))
        assertEquals("2026-10-06T23:59:59", dueAt("Plan this tuesday", TUESDAY_EVENING))
        assertEquals("2026-10-06T23:59:59", dueAt("Plan 6 oct", TUESDAY_EVENING))
    }

    @Test
    fun `a time without a date is tomorrow once it has passed`() {
        assertEquals("2026-10-07T17:00:00", dueAt("Call at 5pm", TUESDAY_EVENING))
        assertEquals("2026-10-07T09:00:00", dueAt("Call 9:00", TUESDAY_EVENING))
        assertEquals("2026-10-06T22:30:00", dueAt("Call at 22:30", TUESDAY_EVENING))
    }

    @Test
    fun `a time equal to now is still today`() {
        assertEquals("2026-10-06T21:00:00", dueAt("Call at 9pm", TUESDAY_EVENING))
    }

    @Test
    fun `a weekday with a time that has passed today means next week`() {
        assertEquals("2026-10-13T09:00:00", dueAt("Standup tuesday 9am"))
        assertEquals("2026-10-06T11:00:00", dueAt("Standup tuesday 11am"))
        // An explicit calendar date is never moved to another year because of the time.
        assertEquals("2026-10-06T09:00:00", dueAt("Standup oct 6 9am"))
    }

    // --- Weeks start on Monday -----------------------------------------------------------------

    @Test
    fun `next weekday is that day in the following Monday-start week`() {
        assertEquals("2026-10-05T23:59:59", dueAt("Call next monday", SUNDAY))
        assertEquals("2026-10-09T23:59:59", dueAt("Call next friday", SUNDAY))
        assertEquals("2026-10-05T23:59:59", dueAt("Call monday", SUNDAY))
        assertEquals("2026-10-09T23:59:59", dueAt("Call this friday", SUNDAY))
        assertEquals("2026-10-05T23:59:59", dueAt("Plan next week", SUNDAY))
    }

    @Test
    fun `next month keeps the day and clamps to the end of a shorter month`() {
        assertEquals("2026-11-06T23:59:59", dueAt("Plan next month"))
        assertEquals("2026-02-28T23:59:59", dueAt("Plan next month", "2026-01-31T10:00:00"))
    }

    @Test
    fun `in N days and weeks are date-only and in N hours is an exact time`() {
        val days = parseAt("Follow up in 3 days")
        assertEquals("2026-10-09T23:59:59", dueOf(days))
        assertFalse(days.dueDateHasTime)

        val hours = parseAt("Call back in 2 hours")
        assertEquals("2026-10-06T12:00:00", dueOf(hours))
        assertTrue(hours.dueDateHasTime)
    }

    @Test
    fun `in N hours counts real time across a clock change`() {
        // Amsterdam sets its clocks back at 03:00 on 2026-10-25: 01:30 plus two hours is 02:30.
        assertEquals("2026-10-25T02:30:00", dueAt("Call back in 2 hours", "2026-10-25T01:30:00"))
    }

    // --- Abbreviations and connectors ----------------------------------------------------------

    @Test
    fun `three-letter abbreviations count only after a connector word or before a time`() {
        assertEquals("2026-10-09T23:59:59", dueAt("Pay rent due fri"))
        assertEquals("2026-10-17T23:59:59", dueAt("Pay rent next sat"))
        assertEquals("2026-10-10T23:59:59", dueAt("Pay rent this sat"))
        assertEquals("2026-10-09T14:00:00", dueAt("Pay rent fri 14:00"))
        assertEquals("2026-10-09T15:00:00", dueAt("Pay rent fri at 3pm"))
        assertNull(dueAt("Visit sat in the sun"))
        assertNull(dueAt("Wed cake"))
    }

    @Test
    fun `thurs and tues are abbreviations too`() {
        assertEquals("2026-10-08T23:59:59", dueAt("Call by thurs"))
        assertEquals("2026-10-13T09:00:00", dueAt("Call tues 9am"))
    }

    @Test
    fun `connectors go with the date and a connector without a date stays`() {
        val by = parseAt("Submit by 5pm")
        assertEquals("Submit", by.title)
        assertEquals("2026-10-06T17:00:00", dueOf(by))

        assertEquals("Taxes", parseAt("Taxes due tomorrow at 9am").title)
        assertEquals("Call", parseAt("Call on friday").title)

        val none = parseAt("Meet at the pub")
        assertEquals("Meet at the pub", none.title)
        assertNull(none.dueDate)
        assertEquals("Call on", parseAt("Call on").title)
    }

    @Test
    fun `a time can come before the date`() {
        assertEquals("2026-10-07T15:00:00", dueAt("Call at 3pm tomorrow"))
        assertEquals("2026-10-09T15:00:00", dueAt("Call 3pm on friday"))
        assertEquals("Call", parseAt("Call at 3pm tomorrow").title)
    }

    @Test
    fun `a time can follow a month-name date`() {
        assertEquals("2026-10-15T09:30:00", dueAt("Party oct 15 9:30am"))
        assertEquals("2027-01-15T21:00:00", dueAt("Party 15 jan at 9pm"))
    }

    // --- Slash dates and years -----------------------------------------------------------------

    @Test
    fun `slash dates follow the locale and flip when the first number cannot be a month`() {
        fun due(locale: String, input: String) = dueAt(input, config = todoist.copy(locale = locale))
        assertEquals("2027-05-11T23:59:59", due("en-US", "Report 5/11"))
        assertEquals("2026-11-05T23:59:59", due("en-GB", "Report 5/11"))
        assertEquals("2026-11-05T23:59:59", due("de-DE", "Report 5/11"))
        assertEquals("2026-11-05T23:59:59", due("nl-NL", "Report 5/11"))
        assertEquals("2026-10-15T23:59:59", due("en-US", "Report 15/10"))
        assertEquals("2026-10-15T23:59:59", due("en-GB", "Report 10/15"))
    }

    @Test
    fun `a slash date that is no date in either order is plain text`() {
        val r = parseAt("Split 13/13 ways")
        assertNull(r.dueDate)
        assertEquals("Split 13/13 ways", r.title)
    }

    @Test
    fun `an explicit year is kept even when the date has passed`() {
        assertEquals("2025-01-15T23:59:59", dueAt("Archive jan 15 2025"))
        assertEquals("2027-01-15T23:59:59", dueAt("Party 15 jan 2027"))
        assertEquals("2027-10-15T23:59:59", dueAt("Report 15/10/2027", config = todoist.copy(locale = "en-GB")))
        assertEquals("2026-10-15T23:59:59", dueAt("Report 10/15/26"))
    }

    @Test
    fun `a date that does not exist is not a date`() {
        assertNull(dueAt("Party feb 30"))
        assertNull(dueAt("Report 2026-02-30"))
    }

    @Test
    fun `device locales order slash dates the way their short date does`() {
        assertFalse(isDayFirstLocale("en-US"))
        assertFalse(isDayFirstLocale("ja-JP"))
        assertTrue(isDayFirstLocale("en-GB"))
        assertTrue(isDayFirstLocale("nl-NL"))
        assertTrue(isDayFirstLocale("de-DE"))
        assertTrue(isDayFirstLocale("fr-FR"))
        assertTrue(deviceLocaleTag().isNotBlank())
    }

    // --- Priority words ------------------------------------------------------------------------

    @Test
    fun `med and critical are priority words in both modes`() {
        for (config in listOf(todoist, vikunja)) {
            assertEquals(2, parseAt("Fix bug !med", config = config).priority)
            assertEquals(2, parseAt("Fix bug !medium", config = config).priority)
            assertEquals(4, parseAt("Outage !critical", config = config).priority)
            assertEquals(4, parseAt("Outage !urgent", config = config).priority)
        }
    }

    @Test
    fun `a leading priority word is not the bang shortcut`() {
        for (word in listOf("low", "med", "medium", "high", "urgent", "critical")) {
            val r = parseAt("!$word Fix bug")
            assertEquals("Fix bug", r.title, word)
            assertNull(r.dueDate, word)
        }
        assertNull(extractBangToday("!med Fix bug").dueDate)
        assertNull(extractBangToday("!critical Fix bug").dueDate)
        assertEquals("Fix bug", extractBangToday("!Fix bug").title)
    }

    // --- The ! shortcut ------------------------------------------------------------------------

    @Test
    fun `a trailing bang after an explicit date is removed and the date wins`() {
        val friday = parseAt("Pay rent friday !")
        assertEquals("Pay rent", friday.title)
        assertEquals("2026-10-09T23:59:59", dueOf(friday))

        val timed = parseAt("Call at 3pm!")
        assertEquals("Call", timed.title)
        assertEquals("2026-10-06T15:00:00", dueOf(timed))
        assertTrue(timed.dueDateHasTime)
    }

    @Test
    fun `the bang shortcut is today date-only from the reference`() {
        val r = parseAt("call dentist !", TUESDAY_EVENING)
        assertEquals("2026-10-06T23:59:59", dueOf(r))
        assertFalse(r.dueDateHasTime)
        assertEquals("2026-10-06T23:59:59", wallOf(extractBangToday("!", LocalDateTime.parse(TUESDAY_EVENING)).dueDate!!))
    }

    @Test
    fun `the bang shortcut works when the parser is off and only when bangToday is on`() {
        val off = ParserConfig(enabled = false, bangToday = true)
        val r = TaskParser.parse("call dentist !", off, LocalDateTime.parse(TUESDAY_MORNING), amsterdam)
        assertEquals("call dentist", r.title)
        assertEquals("2026-10-06T23:59:59", dueOf(r))
        assertFalse(r.dueDateHasTime)
        assertTrue(r.tokens.isEmpty(), "a disabled parser highlights nothing")

        val leading = TaskParser.parse("! call dentist", off, LocalDateTime.parse(TUESDAY_MORNING), amsterdam)
        assertEquals("call dentist", leading.title)
        assertEquals("2026-10-06T23:59:59", dueOf(leading))

        val noBang = off.copy(bangToday = false)
        val plain = TaskParser.parse("call dentist !", noBang, LocalDateTime.parse(TUESDAY_MORNING), amsterdam)
        assertEquals("call dentist !", plain.title)
        assertNull(plain.dueDate)

        // Nothing else is extracted while the parser is off.
        val text = TaskParser.parse("buy milk tomorrow @home p1", off, LocalDateTime.parse(TUESDAY_MORNING), amsterdam)
        assertEquals("buy milk tomorrow @home p1", text.title)
        assertNull(text.dueDate)
    }

    @Test
    fun `a bang inside the text is not a date`() {
        val r = parseAt("Hello! world")
        assertEquals("Hello! world", r.title)
        assertNull(r.dueDate)
    }

    // --- Recurrence ----------------------------------------------------------------------------

    @Test
    fun `shorthand counts only as the last word`() {
        assertEquals(ParsedRecurrence(2, RecurrenceUnit.WEEK), parseAt("Sync biweekly").recurrence)
        assertEquals(ParsedRecurrence(1, RecurrenceUnit.YEAR), parseAt("Renew annually").recurrence)
        assertEquals(ParsedRecurrence(1, RecurrenceUnit.DAY), parseAt("Water plants daily!").recurrence)
        assertNull(parseAt("Weekly sync notes").recurrence)
        assertNull(parseAt("Daily review tomorrow").recurrence)
        assertEquals("Daily review", parseAt("Daily review tomorrow").title)
        assertEquals(ParsedRecurrence(1, RecurrenceUnit.WEEK), parseAt("Plan weekly @work p2").recurrence)
    }

    @Test
    fun `every weekday is weekly and the weekday is the due date`() {
        val r = parseAt("Gym every friday")
        assertEquals(ParsedRecurrence(1, RecurrenceUnit.WEEK), r.recurrence)
        assertEquals("2026-10-09T23:59:59", dueOf(r))
        assertEquals("Gym", r.title)

        val today = parseAt("Standup every tuesday")
        assertEquals("2026-10-06T23:59:59", dueOf(today), "today counts as the next occurrence")
    }

    @Test
    fun `a time after every weekday applies and a passed time moves to next week`() {
        assertEquals("2026-10-12T10:00:00", dueAt("Standup every monday 10am"))
        assertEquals("2026-10-12T10:00:00", dueAt("Standup every monday at 10am", "2026-10-05T11:00:00"))
        assertEquals("2026-10-05T10:00:00", dueAt("Standup every monday at 10am", "2026-10-05T09:00:00"))
    }

    @Test
    fun `another date in the input wins over the weekday of every`() {
        val r = parseAt("Gym every friday starting oct 20")
        assertEquals(ParsedRecurrence(1, RecurrenceUnit.WEEK), r.recurrence)
        assertEquals("2026-10-20T23:59:59", dueOf(r))
        val recurrenceToken = r.tokens.single { it.type == TokenType.RECURRENCE }
        assertEquals("every friday", recurrenceToken.raw)
    }

    @Test
    fun `every weekday leaves the weekday to the date token`() {
        val r = parseAt("Gym every friday")
        assertEquals("every", r.tokens.single { it.type == TokenType.RECURRENCE }.raw)
        assertEquals("friday", r.tokens.single { it.type == TokenType.DATE }.raw)
    }

    @Test
    fun `dismissing the date keeps every weekday as the recurrence only`() {
        val config = todoist.copy(suppressTypes = setOf(TokenType.DATE))
        val r = parseAt("Gym every friday", config = config)
        assertEquals(ParsedRecurrence(1, RecurrenceUnit.WEEK), r.recurrence)
        assertNull(r.dueDate)
        assertEquals("Gym", r.title)
    }

    // --- Tokens --------------------------------------------------------------------------------

    @Test
    fun `the date token covers the connector and the whole phrase`() {
        val input = "Taxes due tomorrow at 9am"
        val token = parseAt(input).tokens.single { it.type == TokenType.DATE }
        assertEquals("due tomorrow at 9am", token.raw)
        assertEquals(input.indexOf("due"), token.start)
        assertEquals(input.length, token.end)
    }

    private companion object {
        const val TUESDAY_MORNING = "2026-10-06T10:00:00"
        const val TUESDAY_EVENING = "2026-10-06T21:00:00"
        const val SUNDAY = "2026-10-04T12:00:00"
    }
}
