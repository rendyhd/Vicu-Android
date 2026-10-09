package com.rendyhd.vicu.ui.components.task

import com.rendyhd.vicu.util.DateContext
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.DateDisplayFormat
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

    private val gb24 = DateDisplayFormat(Locale.UK, hour12 = false)
    private val us12 = DateDisplayFormat(Locale.US, hour12 = true)

    @Test
    fun `a time keeps its minutes`() {
        val due = LocalDateTime(2026, 10, 8, 15, 30)
        assertEquals("Thu 8 Oct, 15:30", formatDateChip(due, hasTime = true, today = today, dateFormat = gb24))
        assertEquals("Thu, Oct 8, 3:30 PM", formatDateChip(due, hasTime = true, today = today, dateFormat = us12))
    }

    @Test
    fun `times just after midnight and just before it are shown`() {
        assertEquals(
            "Wed 7 Oct, 23:45",
            formatDateChip(LocalDateTime(2026, 10, 7, 23, 45), hasTime = true, today = today, dateFormat = gb24),
        )
        assertEquals(
            "Wed 7 Oct, 00:05",
            formatDateChip(LocalDateTime(2026, 10, 7, 0, 5), hasTime = true, today = today, dateFormat = gb24),
        )
        assertEquals(
            "Wed, Oct 7, 12:05 AM",
            formatDateChip(LocalDateTime(2026, 10, 7, 0, 5), hasTime = true, today = today, dateFormat = us12),
        )
    }

    @Test
    fun `a date without a time shows no time, even at midnight`() {
        assertEquals(
            "Wed 7 Oct",
            formatDateChip(LocalDateTime(2026, 10, 7, 0, 0), hasTime = false, today = today, dateFormat = gb24),
        )
        assertEquals(
            "Tue 20 Oct",
            formatDateChip(LocalDateTime(2026, 10, 20, 0, 0), hasTime = false, today = today, dateFormat = gb24),
        )
    }

    @Test
    fun `a chip always names the weekday date, never a relative word`() {
        fun chip(date: LocalDateTime, hasTime: Boolean = false, format: DateDisplayFormat = gb24) =
            formatDateChip(date, hasTime = hasTime, today = today, dateFormat = format)

        // "Call Ana about Saturday" typed on a Wednesday used to show "2026-10-10".
        assertEquals("Sat 10 Oct", chip(LocalDateTime(2026, 10, 10, 23, 59, 59)))
        assertEquals("Sat 10 Oct, 15:00", chip(LocalDateTime(2026, 10, 10, 15, 0), hasTime = true))
        assertEquals("Sat, Oct 10, 3:00 PM", chip(LocalDateTime(2026, 10, 10, 15, 0), hasTime = true, format = us12))
        assertEquals("Tue 13 Oct", chip(LocalDateTime(2026, 10, 13, 23, 59, 59)))
        assertEquals("Fri 15 Jan 2027", chip(LocalDateTime(2027, 1, 15, 23, 59, 59)))
        assertEquals("Tue 6 Oct", chip(LocalDateTime(2026, 10, 6, 23, 59, 59)))
    }

    @Test
    fun `the chip names a date the same way as the date chip of the sheet`() {
        // The sheet's date chip shows the stored value with DateDisplay.formatDue.
        val zone = TimeZone.of("Europe/Amsterdam")
        val parsed = listOf(
            LocalDateTime(2026, 10, 7, 23, 59, 59) to false,
            LocalDateTime(2026, 10, 8, 9, 30) to true,
            LocalDateTime(2026, 10, 10, 23, 59, 59) to false,
            LocalDateTime(2026, 10, 10, 23, 45) to true,
            LocalDateTime(2026, 10, 16, 23, 59, 59) to false,
            LocalDateTime(2027, 3, 3, 14, 0) to true,
        )
        for (format in listOf(gb24, us12)) {
            for ((date, hasTime) in parsed) {
                val stored = DueDates.fromParsed(date, hasTime, zone).toString()
                assertEquals(
                    DateDisplay.formatDue(DateContext.CHIP, stored, today, zone, format),
                    formatDateChip(date, hasTime, today, format),
                    "$date hasTime=$hasTime $format",
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
