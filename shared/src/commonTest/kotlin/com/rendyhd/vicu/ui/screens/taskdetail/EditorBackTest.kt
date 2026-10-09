package com.rendyhd.vicu.ui.screens.taskdetail

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorBackTest {

    @Test
    fun `at rest the editor is full size and opaque`() {
        assertEquals(1f, editorBackScale(0f), 0f)
        assertEquals(1f, editorBackAlpha(0f), 0f)
    }

    @Test
    fun `held fully the editor is at 0_9 and half faded`() {
        assertEquals(0.9f, editorBackScale(1f), 1e-6f)
        assertEquals(0.5f, editorBackAlpha(1f), 1e-6f)
    }

    @Test
    fun `halfway is halfway and out of range is clamped`() {
        assertEquals(0.95f, editorBackScale(0.5f), 1e-6f)
        assertEquals(0.9f, editorBackScale(7f), 1e-6f)
        assertEquals(1f, editorBackAlpha(-3f), 0f)
    }

    @Test
    fun `a cancelled gesture ends at rest even with animations off`() = runTest {
        val state = EditorBackState()
        state.progress = 0.6f
        val off = object : MotionDurationScale {
            override val scaleFactor: Float = 0f
        }
        // A frame clock that ticks 16 ms per frame stands in for the display.
        var nanos = 0L
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                nanos += 16_000_000L
                return onFrame(nanos)
            }
        }
        withContext(off + clock) { state.springBack() }
        assertEquals(0f, state.progress, 0f)
    }
}
