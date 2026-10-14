package com.rendyhd.vicu.ui.components.task

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The swipe's haptic and its action use one commit point: half the row's width. */
class SwipeCommitTest {

    @Test
    fun `the row commits from half its width in either direction`() {
        assertFalse(SwipeCommit.committed(SwipeCommit.fraction(offsetPx = 99f, widthPx = 200f)))
        assertTrue(SwipeCommit.committed(SwipeCommit.fraction(offsetPx = 100f, widthPx = 200f)))
        assertTrue(SwipeCommit.committed(SwipeCommit.fraction(offsetPx = -120f, widthPx = 200f)))
    }

    @Test
    fun `an unknown width or offset never commits`() {
        assertEquals(0f, SwipeCommit.fraction(offsetPx = 150f, widthPx = 0f))
        assertEquals(0f, SwipeCommit.fraction(offsetPx = Float.NaN, widthPx = 200f))
    }

    @Test
    fun `the crossing fires once per crossing, not once per frame and not for a short drag or a flick`() = runTest {
        // A short drag, a drag over the line (several frames), back under it, and over it again.
        val drag = flowOf(0f, 0.2f, 0.45f, 0.5f, 0.6f, 0.9f, 0.4f, 0.1f, 0.55f)

        val changes = drag.commitCrossings().toList()

        assertEquals(listOf(false, true, false, true), changes)
        assertEquals(2, changes.count { it }, "two crossings, two haptics")
    }

    @Test
    fun `a gesture that never reaches half way gives no haptic`() = runTest {
        val changes = flowOf(0f, 0.1f, 0.3f, 0.49f, 0f).commitCrossings().toList()

        assertEquals(listOf(false), changes)
    }
}
