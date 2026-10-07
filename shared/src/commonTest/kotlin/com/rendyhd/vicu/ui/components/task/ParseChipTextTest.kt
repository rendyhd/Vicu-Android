package com.rendyhd.vicu.ui.components.task

import com.rendyhd.vicu.util.parser.TokenType
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The text of the chips under the entry field, and what a screen reader says for each. */
class ParseChipTextTest {

    private val today = LocalDate(2026, 10, 7)

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
            "2026-10-20",
            formatDateChip(LocalDateTime(2026, 10, 20, 0, 0), hasTime = false, today = today, is24Hour = true),
        )
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
