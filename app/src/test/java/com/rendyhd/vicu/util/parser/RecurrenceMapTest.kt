package com.rendyhd.vicu.util.parser

import org.junit.Assert.assertEquals
import org.junit.Test

class RecurrenceMapTest {

    @Test
    fun `daily = 86400s, mode 0`() {
        val result = recurrenceToVikunja(ParsedRecurrence(1, RecurrenceUnit.DAY))
        assertEquals(VikunjaRecurrence(86400L, 0), result)
    }

    @Test
    fun `weekly = 604800s, mode 0`() {
        val result = recurrenceToVikunja(ParsedRecurrence(1, RecurrenceUnit.WEEK))
        assertEquals(VikunjaRecurrence(604800L, 0), result)
    }

    @Test
    fun `monthly = 0, mode 1`() {
        val result = recurrenceToVikunja(ParsedRecurrence(1, RecurrenceUnit.MONTH))
        assertEquals(VikunjaRecurrence(0L, 1), result)
    }

    @Test
    fun `yearly = 365 x 86400, mode 0`() {
        val result = recurrenceToVikunja(ParsedRecurrence(1, RecurrenceUnit.YEAR))
        assertEquals(VikunjaRecurrence(365L * 86400L, 0), result)
    }

    @Test
    fun `every 2 weeks = 2 x 604800, mode 0`() {
        val result = recurrenceToVikunja(ParsedRecurrence(2, RecurrenceUnit.WEEK))
        assertEquals(VikunjaRecurrence(2L * 604800L, 0), result)
    }
}
