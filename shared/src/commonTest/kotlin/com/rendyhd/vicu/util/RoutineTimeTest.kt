package com.rendyhd.vicu.util

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutineTimeTest {

    @Test
    fun `timestamps are written with exactly three fraction digits`() {
        assertEquals("2026-10-06T08:00:00.000Z", RoutineTime.format(Instant.parse("2026-10-06T08:00:00Z")))
        assertEquals("2026-10-06T08:00:00.100Z", RoutineTime.format(Instant.parse("2026-10-06T08:00:00.1Z")))
        assertEquals("2026-10-06T08:00:00.123Z", RoutineTime.format(Instant.parse("2026-10-06T08:00:00.123456789Z")))
        assertEquals("2026-01-02T03:04:05.006Z", RoutineTime.format(Instant.parse("2026-01-02T03:04:05.006Z")))
    }

    @Test
    fun `a timestamp is always UTC whatever the instant came from`() {
        assertEquals("2026-10-05T22:00:00.000Z", RoutineTime.format(Instant.parse("2026-10-06T00:00:00+02:00")))
    }

    @Test
    fun `now follows the injected time source`() {
        val time = FixedTimeSource(Instant.parse("2026-10-06T08:00:00.5Z"), kotlinx.datetime.TimeZone.UTC)

        assertEquals("2026-10-06T08:00:00.500Z", RoutineTime.now(time))
    }

    @Test
    fun `parse accepts timestamps with or without a fraction and rejects the rest`() {
        assertEquals(Instant.parse("2026-10-01T08:00:00Z"), RoutineTime.parse("2026-10-01T08:00:00Z"))
        assertEquals(Instant.parse("2026-10-01T08:00:00.100Z"), RoutineTime.parse("2026-10-01T08:00:00.100Z"))
        assertNull(RoutineTime.parse(""))
        assertNull(RoutineTime.parse("   "))
        assertNull(RoutineTime.parse("yesterday"))
        assertNull(RoutineTime.parse(null))
    }

    @Test
    fun `the later instant wins even when the string would sort the other way`() {
        // "...00Z" sorts after "...00.100Z" as text ('Z' > '.'), but is the earlier instant.
        assertTrue(
            RoutineTime.compareWrites("2026-10-01T08:00:00Z", "z", "2026-10-01T08:00:00.100Z", "a") < 0,
        )
        assertTrue(
            RoutineTime.compareWrites("2026-10-01T08:00:00.100Z", "a", "2026-10-01T08:00:00Z", "z") > 0,
        )
    }

    @Test
    fun `equal instants are decided by the larger device id`() {
        assertTrue(RoutineTime.compareWrites("2026-10-01T08:00:00Z", "b", "2026-10-01T08:00:00.000Z", "a") > 0)
        assertTrue(RoutineTime.compareWrites("2026-10-01T08:00:00Z", "a", "2026-10-01T08:00:00.000Z", "b") < 0)
        assertEquals(0, RoutineTime.compareWrites("2026-10-01T08:00:00Z", "a", "2026-10-01T08:00:00.000Z", "a"))
    }

    @Test
    fun `a timestamp that does not parse loses to every real one`() {
        assertTrue(RoutineTime.compareWrites("garbage", "z", "2020-01-01T00:00:00Z", "a") < 0)
        assertTrue(RoutineTime.compareWrites("2020-01-01T00:00:00Z", "a", "", "z") > 0)
    }
}
