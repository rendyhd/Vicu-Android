package com.rendyhd.vicu.util

import kotlin.test.Test
import kotlin.test.assertEquals

class SnoozeRulesTest {

    @Test
    fun `snoozing fires the reminder fifteen minutes from the time source's now`() {
        val now = 1_000_000_000L

        assertEquals(now + 15 * 60_000L, SnoozeRules.triggerAfterSnooze(now))
    }

    @Test
    fun `a snooze that is still in the future keeps its time after a reboot`() {
        val now = 1_000_000_000L
        val stored = now + 5 * 60_000L

        assertEquals(stored, SnoozeRules.triggerAfterRestore(stored, now))
    }

    @Test
    fun `a snooze that came due while the device was off fires a minute after it is restored`() {
        val now = 1_000_000_000L

        assertEquals(now + 60_000L, SnoozeRules.triggerAfterRestore(now - 3_600_000L, now))
        assertEquals(now + 60_000L, SnoozeRules.triggerAfterRestore(now + 10_000L, now), "inside the grace period too")
    }
}
