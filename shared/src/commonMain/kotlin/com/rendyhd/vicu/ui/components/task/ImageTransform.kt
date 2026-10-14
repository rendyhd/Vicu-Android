package com.rendyhd.vicu.ui.components.task

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified

/**
 * The zoom and pan of the image in the viewer, as a layer scaled about its centre by [scale] and
 * moved by [offset].
 */
internal data class ImageTransform(val scale: Float, val offset: Offset) {

    /**
     * This transform after a pinch or drag: [zoomChange] times the scale about [centroid] (a point
     * in the layer; unspecified means its centre), then [panChange]. The scale stays between
     * whole and [MAX_SCALE], and the image cannot be moved so far that part of the screen shows
     * nothing; a whole image does not move.
     */
    fun transformedBy(zoomChange: Float, panChange: Offset, centroid: Offset, size: Size): ImageTransform {
        val newScale = (scale * zoomChange).coerceIn(1f, MAX_SCALE)
        if (newScale <= 1f) return Identity
        val applied = newScale / scale
        // Keep the point under the centroid still: it sits at centre + (q - centre) * s + t.
        val around = if (centroid.isSpecified) {
            centroid - Offset(size.width / 2f, size.height / 2f)
        } else {
            Offset.Zero
        }
        val moved = around * (1f - applied) + offset * applied + panChange
        val maxX = size.width * (newScale - 1f) / 2f
        val maxY = size.height * (newScale - 1f) / 2f
        return ImageTransform(newScale, Offset(moved.x.coerceIn(-maxX, maxX), moved.y.coerceIn(-maxY, maxY)))
    }

    companion object {
        const val MAX_SCALE = 5f
        val Identity = ImageTransform(1f, Offset.Zero)
    }
}
