package com.rendyhd.vicu.ui.components.task

import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.util.parser.TokenType
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Before
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The text of the chips under the entry field, and what a screen reader says for each. */
class ParseChipTextTest {

    private val today = LocalDate(2026, 10, 7) // a Wednesday
    private var savedLocale: Locale = Locale.getDefault()

    @Before
    fun fixLocale() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(savedLocale)
    }

    @Test
    fun `a time keeps its minutes`() {
        val due = LocalDateTime(2026, 10, 8, 15, 30)
        assertEquals("Tomorrow 15:30", formatDateChip(due, hasTime = true, today = today, is24Hour = true))
        assertTrue("3:30" in formatDateChip(due, hasTime = true, today = today, is24Hour = false))
    }

    @Test
    fun `times just after midnight and just before it are shown`() {
        assertEquals(
            "Today 23:45",
            formatDateChip(LocalDateTime(2026, 10, 7, 23, 45), hasTime = true, today = today, is24Hour = true),
        )
        assertEquals(
            "Today 00:05",
            formatDateChip(LocalDateTime(2026, 10, 7, 0, 5), hasTime = true, today = today, is24Hour = true),
        )
    }

    @Test
    fun `a date without a time shows no time, even at midnight`() {
        assertEquals(
            "Today",
            formatDateChip(LocalDateTime(2026, 10, 7, 0, 0), hasTime = false, today = today, is24Hour = true),
        )
        assertEquals(
            "Oct 20",
            formatDateChip(LocalDateTime(2026, 10, 20, 0, 0), hasTime = false, today = today, is24Hour = true),
        )
    }

    @Test
    fun `a date after tomorrow is named the way the task list names it`() {
        fun chip(date: LocalDateTime, hasTime: Boolean = false) =
            formatDateChip(date, hasTime = hasTime, today = today, is24Hour = true)

        // "Call Ana about Saturday" typed on a Wednesday used to show "2026-10-10".
        assertEquals("Sat", chip(LocalDateTime(2026, 10, 10, 23, 59, 59)))
        assertEquals("Sat 15:00", chip(LocalDateTime(2026, 10, 10, 15, 0), hasTime = true))
        assertEquals("Tue", chip(LocalDateTime(2026, 10, 13, 23, 59, 59)))
        // A week ahead is the same weekday as today, so it is a date.
        assertEquals("Oct 14", chip(LocalDateTime(2026, 10, 14, 23, 59, 59)))
        assertEquals("Jan 15, 2027", chip(LocalDateTime(2027, 1, 15, 23, 59, 59)))
        assertEquals("Yesterday", chip(LocalDateTime(2026, 10, 6, 23, 59, 59)))
    }

    @Test
    fun `the chip names a date the same way as the date chip of the sheet`() {
        // The sheet's date chip shows the stored value with DateUtils.formatDueDate.
        val zone = TimeZone.of("Europe/Amsterdam")
        val parsed = listOf(
            LocalDateTime(2026, 10, 7, 23, 59, 59) to false,
            LocalDateTime(2026, 10, 8, 9, 30) to true,
            LocalDateTime(2026, 10, 10, 23, 59, 59) to false,
            LocalDateTime(2026, 10, 10, 23, 45) to true,
            LocalDateTime(2026, 10, 16, 23, 59, 59) to false,
            LocalDateTime(2027, 3, 3, 14, 0) to true,
        )
        for (is24Hour in listOf(true, false)) {
            for ((date, hasTime) in parsed) {
                val stored = DueDates.fromParsed(date, hasTime, zone).toString()
                assertEquals(
                    DateUtils.formatDueDate(stored, today, is24Hour, zone),
                    formatDateChip(date, hasTime, today, is24Hour),
                    "$date hasTime=$hasTime is24Hour=$is24Hour",
                )
            }
        }
    }

    @Test
    fun `each dismiss button says what it removes`() {
        val descriptions = listOf(
            chipDismissDescription(TokenType.DATE, "Today"),
            chipDismissDescription(TokenType.PRIORITY, "High"),
            chipDismissDescription(TokenType.LABEL, "errand"),
            chipDismissDescription(TokenType.LABEL, "home"),
            chipDismissDescription(TokenType.PROJECT, "Work"),
            chipDismissDescription(TokenType.RECURRENCE, "Every week"),
        )
        assertEquals(descriptions.size, descriptions.toSet().size, "no two buttons share a description")
        assertEquals("Remove label errand", descriptions[2])
        assertEquals("Remove project Work", descriptions[4])
        assertEquals("Remove due date", descriptions[0])
    }
}
