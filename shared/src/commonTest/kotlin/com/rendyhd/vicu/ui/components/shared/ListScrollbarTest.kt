package com.rendyhd.vicu.ui.components.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ListScrollbarTest {
    private fun thumb(
        firstIndex: Int = 0,
        firstOffset: Int = 0,
        total: Int = 100,
        atStart: Boolean = false,
        atEnd: Boolean = false,
    ) = scrollbarThumb(
        firstIndex = firstIndex,
        firstOffset = firstOffset,
        averageItem = 100f,
        totalItems = total,
        viewport = 1000f,
        minThumb = 80f,
        atStart = atStart,
        atEnd = atEnd,
    )

    @Test
    fun aListThatFitsHasNoThumb() {
        assertNull(thumb(total = 5, atStart = true, atEnd = true))
    }

    @Test
    fun theThumbIsAsLongAsTheShareOnScreen() {
        assertEquals(100f, assertNotNull(thumb(atStart = true)).size)
    }

    @Test
    fun aVeryLongListKeepsAThumbYouCanSee() {
        assertEquals(80f, assertNotNull(thumb(total = 10_000, atStart = true)).size)
    }

    @Test
    fun theEndsAreExact() {
        assertEquals(0f, assertNotNull(thumb(firstIndex = 3, atStart = true)).offset)
        assertEquals(900f, assertNotNull(thumb(firstIndex = 50, atEnd = true)).offset)
    }

    @Test
    fun theMiddleFollowsTheEstimate() {
        // 4500 of 9000 scrollable: halfway along the 900 the thumb can travel.
        assertEquals(450f, assertNotNull(thumb(firstIndex = 45)).offset)
    }

    @Test
    fun aListEstimatedShorterThanTheViewportStillDrawsWhileItCanScroll() {
        // Tall rows above short ones can make the estimate smaller than the screen.
        val short = scrollbarThumb(1, 0, 50f, 10, 1000f, 80f, atStart = false, atEnd = false)
        assertTrue(assertNotNull(short).size <= 1000f)
    }

    @Test
    fun aDragAsksForTheRowUnderTheThumb() {
        val target = scrollbarTarget(thumbOffset = 450f, thumbSize = 100f, averageItem = 100f, totalItems = 100, viewport = 1000f)
        assertEquals(ScrollbarTarget(45, 0), target)
    }

    @Test
    fun aDragPastTheEndsStaysInTheList() {
        assertEquals(ScrollbarTarget(0, 0), scrollbarTarget(-50f, 100f, 100f, 100, 1000f))
        assertEquals(ScrollbarTarget(90, 0), scrollbarTarget(5000f, 100f, 100f, 100, 1000f))
    }

    @Test
    fun aDragKeepsTheOffsetWithinARow() {
        assertEquals(ScrollbarTarget(45, 50), scrollbarTarget(455f, 100f, 100f, 100, 1000f))
    }
}
