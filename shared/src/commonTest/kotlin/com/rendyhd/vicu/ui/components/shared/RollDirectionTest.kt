package com.rendyhd.vicu.ui.components.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The way a count rolls (card 4.11b); the same rule as the desktop `rollDirection`. */
class RollDirectionTest {
    @Test
    fun `a larger count rolls up`() {
        assertEquals(RollDirection.UP, rollDirection(3, 4))
        assertEquals(RollDirection.UP, rollDirection(0, 1))
        assertEquals(RollDirection.UP, rollDirection(9, 10))
    }

    @Test
    fun `a smaller count rolls down`() {
        assertEquals(RollDirection.DOWN, rollDirection(4, 3))
        assertEquals(RollDirection.DOWN, rollDirection(10, 9))
        assertEquals(RollDirection.DOWN, rollDirection(1, 0))
    }

    @Test
    fun `an unchanged count does not roll`() {
        assertNull(rollDirection(5, 5))
        assertNull(rollDirection(0, 0))
    }
}
