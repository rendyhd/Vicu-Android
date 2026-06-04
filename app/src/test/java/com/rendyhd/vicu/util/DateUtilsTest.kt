package com.rendyhd.vicu.util

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.abs

class DateUtilsTest {

    @Test
    fun `isoDaysAgo returns a parseable UTC instant about N days before now`() {
        val days = 90
        val parsed = DateUtils.parseIsoDate(DateUtils.isoDaysAgo(days))
        requireNotNull(parsed) { "isoDaysAgo should produce a parseable ISO instant" }
        val expected = Instant.now().minus(days.toLong(), ChronoUnit.DAYS)
        val diffSeconds = abs(parsed.epochSecond - expected.epochSecond)
        assertTrue("expected within ~5s of now-$days days but diff was ${diffSeconds}s", diffSeconds < 5)
    }

    @Test
    fun `isoDaysAgo with a larger window is earlier`() {
        val recent = DateUtils.parseIsoDate(DateUtils.isoDaysAgo(30))!!
        val older = DateUtils.parseIsoDate(DateUtils.isoDaysAgo(365))!!
        assertTrue("365-day cutoff should be before 30-day cutoff", older.isBefore(recent))
    }

    @Test
    fun `formatRecurrence treats monthly (repeatMode 1, repeatAfter 0) as recurring`() {
        // Regression: monthly recurrence carries repeatAfter==0 and was previously shown as
        // non-recurring because the gate only checked repeatAfter > 0.
        assertTrue(DateUtils.formatRecurrence(0, 1).isNotBlank())
        assertTrue(DateUtils.formatRecurrence(0, 1).contains("month", ignoreCase = true))
    }

    @Test
    fun `formatRecurrence is blank for non-recurring`() {
        assertTrue(DateUtils.formatRecurrence(0, 0).isBlank())
    }

    @Test
    fun `formatRecurrence weekly from repeatAfter`() {
        assertTrue(DateUtils.formatRecurrence(7 * 86400, 0).contains("week", ignoreCase = true))
    }
}
