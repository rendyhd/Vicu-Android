package com.rendyhd.vicu.ui.components.task

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals

/** Where a chip starts when it travels out of its word (card 4.11b); the same rule as the desktop `travelStart`. */
class ChipTravelTest {
    private fun rect(left: Float, top: Float, width: Float, height: Float) = Rect(left, top, left + width, top + height)

    @Test
    fun `the chip starts at the centre of the word, scaled to the width of the word`() {
        val token = rect(left = 100f, top = 20f, width = 60f, height = 20f) // centre 130, 30
        val chip = rect(left = 40f, top = 100f, width = 120f, height = 32f) // centre 100, 116
        val start = travelStart(token, chip)
        assertEquals(30f, start.dx)
        assertEquals(-86f, start.dy)
        assertEquals(0.5f, start.scale)
    }

    @Test
    fun `a word wider than the chip does not grow it`() {
        val start = travelStart(rect(0f, 0f, 300f, 20f), rect(0f, 100f, 100f, 32f))
        assertEquals(1f, start.scale)
    }

    @Test
    fun `a tiny word does not shrink the chip below half`() {
        val start = travelStart(rect(0f, 0f, 10f, 20f), rect(0f, 100f, 100f, 32f))
        assertEquals(0.5f, start.scale)
    }

    @Test
    fun `a word in between scales in proportion`() {
        val start = travelStart(rect(0f, 0f, 75f, 20f), rect(0f, 100f, 100f, 32f))
        assertEquals(0.75f, start.scale)
    }

    @Test
    fun `an empty chip is not scaled`() {
        val start = travelStart(rect(0f, 0f, 75f, 20f), rect(0f, 100f, 0f, 0f))
        assertEquals(1f, start.scale)
    }
}
