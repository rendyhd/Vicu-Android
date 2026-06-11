package com.rendyhd.vicu.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class DateUtilsMidnightTest {

    @Test
    fun `one hour before midnight`() {
        val zone = ZoneId.of("Europe/Amsterdam")
        val now = ZonedDateTime.of(2026, 6, 11, 23, 0, 0, 0, zone)
        assertEquals(60L * 60 * 1000, DateUtils.millisUntilNextMidnight(now))
    }

    @Test
    fun `dst spring-forward day is one hour shorter`() {
        // Europe/Amsterdam springs forward on 2026-03-29 (02:00 -> 03:00).
        val zone = ZoneId.of("Europe/Amsterdam")
        val now = ZonedDateTime.of(2026, 3, 29, 1, 0, 0, 0, zone)
        assertEquals(22L * 60 * 60 * 1000, DateUtils.millisUntilNextMidnight(now))
    }

    @Test
    fun `at exact midnight returns a full day, never zero`() {
        val zone = ZoneId.of("UTC")
        val now = ZonedDateTime.of(2026, 6, 11, 0, 0, 0, 0, zone)
        assertEquals(24L * 60 * 60 * 1000, DateUtils.millisUntilNextMidnight(now))
    }
}
