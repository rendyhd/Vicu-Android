package com.rendyhd.vicu.util.parser

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `for` beyond the corpus (contract section 5.4; the desktop's `parse.test.ts` has the same cases). */
class ForLengthTest {
    private val zone = TimeZone.of("Europe/Amsterdam")
    // Tuesday 6 October 2026, 10:00.
    private val now = LocalDateTime(2026, 10, 6, 10, 0)

    private fun at(text: String, config: ParserConfig = ParserConfig(locale = "en-US")) =
        TaskParser.parse(text, config, now, zone)

    @Test
    fun wordFormsAreNotDates() {
        for (text in listOf("Run for an hour", "Holiday for two weeks", "Stretch for 2d", "Wait for 5s", "Hold for half an hour", "Rest for two weeks and three days")) {
            val result = at(text)
            assertNull(result.dueDate, text)
            assertEquals(text, result.title)
        }
    }

    @Test
    fun wordFormsKeepTwoDatePhrasesApart() {
        val result = at("Call tomorrow for an hour at 9am")
        assertEquals("Call for an hour at 9am", result.title)
        assertFalse(result.dueDateHasTime)
        assertEquals(LocalDate(2026, 10, 7), result.dueDate?.date)
        assertNull(result.block)
    }

    @Test
    fun aLengthInsideADatePhraseLeavesTokensThatDoNotOverlap() {
        val result = at("Call tomorrow for 1h at 9am")
        assertEquals(
            listOf(TokenType.DATE to "tomorrow", TokenType.DATE to "at 9am", TokenType.LENGTH to "for 1h"),
            result.tokens.map { it.type to it.raw },
        )
        val sorted = result.tokens.sortedBy { it.start }
        sorted.zipWithNext().forEach { (a, b) -> assertTrue(b.start >= a.end, "$a overlaps $b") }
    }

    @Test
    fun aPhraseThatIsNotABlockStaysInTheTitleAlsoInsideADatePhrase() {
        val result = at("Call tomorrow for 13h at 9am")
        assertEquals("Call for 13h", result.title)
        assertNull(result.block)
        assertTrue(result.dueDateHasTime)
        assertEquals(LocalDateTime(2026, 10, 7, 9, 0), result.dueDate)
    }

    @Test
    fun dismissingTheLengthKeepsTheTimeAsTheDueTime() {
        val result = at("Call at 3pm for 30m", ParserConfig(locale = "en-US", suppressTypes = setOf(TokenType.LENGTH)))
        assertEquals("Call for 30m", result.title)
        assertNull(result.block)
        assertTrue(result.dueDateHasTime)
        assertEquals(LocalDateTime(2026, 10, 6, 15, 0), result.dueDate)
    }

    @Test
    fun dismissingTheDateLeavesAllTheWords() {
        val result = at("Call at 3pm for 30m", ParserConfig(locale = "en-US", suppressTypes = setOf(TokenType.DATE)))
        assertEquals("Call at 3pm for 30m", result.title)
        assertNull(result.dueDate)
        assertNull(result.block)
    }

    @Test
    fun theDueDateIsDateOnlyAndTheBlockHoldsTheTime() {
        val result = at("Call at 3pm for 30m")
        assertEquals("Call", result.title)
        assertFalse(result.dueDateHasTime)
        val block = assertNotNull(result.block)
        assertEquals(LocalDateTime(2026, 10, 6, 15, 0), block.start)
        assertEquals(LocalDateTime(2026, 10, 6, 15, 30), block.end)
    }

    @Test
    fun hugeNumbersAreNotLengths() {
        val result = at("Call at 3pm for 99999999999999999999h")
        assertNull(result.block)
        assertTrue(result.dueDateHasTime)
    }
}
