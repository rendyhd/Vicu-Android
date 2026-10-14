package com.rendyhd.vicu.ui.components.task

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pinching and panning the image in the viewer: zoom stays under the fingers and in bounds. */
class ImageTransformTest {

    private val size = Size(1000f, 2000f)
    private val center = Offset(500f, 1000f)

    private fun assertClose(expected: Offset, actual: Offset, delta: Float = 0.01f) {
        assertEquals(expected.x, actual.x, delta, "x")
        assertEquals(expected.y, actual.y, delta, "y")
    }

    @Test
    fun `at the start the image is whole and centred`() {
        assertEquals(1f, ImageTransform.Identity.scale)
        assertEquals(Offset.Zero, ImageTransform.Identity.offset)
    }

    @Test
    fun `zooming about the centre does not move the image`() {
        val t = ImageTransform.Identity.transformedBy(zoomChange = 2f, panChange = Offset.Zero, centroid = center, size = size)
        assertEquals(2f, t.scale)
        assertClose(Offset.Zero, t.offset)
    }

    @Test
    fun `the point under the fingers stays under them while zooming`() {
        val centroid = Offset(900f, 300f)
        val t = ImageTransform.Identity.transformedBy(2f, Offset.Zero, centroid, size)
        // A layer scaled about its centre by s and moved by t puts content point q at
        // centre + (q - centre) * s + t. The point that was under the centroid is the centroid.
        val q = centroid
        val onScreen = center + (q - center) * t.scale + t.offset
        assertClose(centroid, onScreen)
    }

    @Test
    fun `an unknown centroid zooms about the centre`() {
        val t = ImageTransform.Identity.transformedBy(3f, Offset.Zero, Offset.Unspecified, size)
        assertClose(Offset.Zero, t.offset)
        assertEquals(3f, t.scale)
    }

    @Test
    fun `the scale stays between whole and five times`() {
        val big = ImageTransform.Identity.transformedBy(100f, Offset.Zero, center, size)
        assertEquals(ImageTransform.MAX_SCALE, big.scale)
        val small = big.transformedBy(0.001f, Offset.Zero, center, size)
        assertEquals(1f, small.scale)
        assertEquals(Offset.Zero, small.offset, "a whole image is not panned")
    }

    @Test
    fun `panning cannot move the image off the screen`() {
        val zoomed = ImageTransform.Identity.transformedBy(2f, Offset.Zero, center, size)
        // At 2x the image is twice the screen: it can move by half the screen either way.
        val far = zoomed.transformedBy(1f, Offset(10_000f, -10_000f), center, size)
        assertClose(Offset(500f, -1000f), far.offset)
        val within = zoomed.transformedBy(1f, Offset(120f, 80f), center, size)
        assertClose(Offset(120f, 80f), within.offset)
    }

    @Test
    fun `the viewer names each image by its place among the images`() {
        assertEquals("Image", imageViewerDescription(index = 0, total = 1))
        assertEquals("Image 1 of 3", imageViewerDescription(0, 3))
        assertEquals("Image 3 of 3", imageViewerDescription(2, 3))
    }

    @Test
    fun `a whole image does not pan at all`() {
        val t = ImageTransform.Identity.transformedBy(1f, Offset(50f, 50f), center, size)
        assertEquals(Offset.Zero, t.offset)
        assertTrue(t.scale == 1f)
    }
}
