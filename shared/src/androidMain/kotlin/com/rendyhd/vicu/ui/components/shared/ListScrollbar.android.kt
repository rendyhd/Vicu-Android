package com.rendyhd.vicu.ui.components.shared

import android.os.Build
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView

/**
 * Compose's systemGestureExclusion only follows a node that moves in layout, and a thumb is drawn:
 * so this writes the view's list itself, keeping the rectangles other nodes put there.
 */
@Composable
internal actual fun rememberEdgeGestureExclusion(): (Rect?) -> Unit {
    val view = LocalView.current
    val exclusion = remember(view) { GestureExclusion(view) }
    DisposableEffect(exclusion) {
        onDispose { exclusion.set(null) }
    }
    return exclusion::set
}

private class GestureExclusion(private val view: View) {
    private var current: android.graphics.Rect? = null

    fun set(rect: Rect?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val next = rect?.let {
            android.graphics.Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt())
        }
        if (next == current) return
        val rects = view.systemGestureExclusionRects.toMutableList()
        current?.let(rects::remove)
        next?.let(rects::add)
        view.systemGestureExclusionRects = rects
        current = next
    }
}
