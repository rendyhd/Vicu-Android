package com.rendyhd.vicu.ui.screens.plan

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** How far from the screen's edges a swipe may start: the edges are Back. */
private val SwipeEdgeKeepOut = 24.dp

/** A swipe must be this much more sideways than up or down to change the day. */
private const val SIDEWAYS_RATIO = 1.5f

/**
 * A sideways swipe that changes the day (issue 101). It lets the children go first: a block's press
 * and hold, the drawer tab and the hours' vertical scroll each keep their own gestures. Once the
 * finger has moved sideways past the touch slop the swipe is this modifier's, and nothing under it
 * sees the rest. [onEnd] gets the sideways speed in pixels per second.
 */
internal fun Modifier.daySwipe(
    enabled: Boolean,
    onStart: () -> Unit,
    onDelta: (Float) -> Unit,
    onEnd: (Float) -> Unit,
): Modifier = composed {
    val latestStart by rememberUpdatedState(onStart)
    val latestDelta by rememberUpdatedState(onDelta)
    val latestEnd by rememberUpdatedState(onEnd)
    val latestEnabled by rememberUpdatedState(enabled)
    // Always attached, and asked whether it is on: taking a pointer input off a parent in the middle
    // of a gesture cancels every gesture under it, such as the block being dragged.
    pointerInput(Unit) {
        val keepOut = SwipeEdgeKeepOut.toPx()
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!latestEnabled) return@awaitEachGesture
            if (down.position.x < keepOut || down.position.x > size.width - keepOut) return@awaitEachGesture
            val velocity = VelocityTracker()
            velocity.addPointerInputChange(down)
            var claimed = false
            var dx = 0f
            var dy = 0f
            while (true) {
                // Once claimed, read first and take every change, so the hours do not scroll as well.
                val event = awaitPointerEvent(if (claimed) PointerEventPass.Initial else PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                // The down itself comes round again in this pass, and a clickable under it (a block, a
                // marker, a chip) has consumed it: only a move that something consumed means it is theirs.
                if (!change.previousPressed) continue
                if (!change.pressed) {
                    if (claimed) {
                        change.consume()
                        latestEnd(velocity.calculateVelocity().x)
                    }
                    return@awaitEachGesture
                }
                val stepX = change.position.x - change.previousPosition.x
                val stepY = change.position.y - change.previousPosition.y
                if (!claimed) {
                    if (!latestEnabled) return@awaitEachGesture
                    // Something under the finger took it: a block's drag, the scroll, the drawer tab.
                    if (change.isConsumed) return@awaitEachGesture
                    dx += stepX
                    dy += stepY
                    if (abs(dy) > slop && abs(dy) >= abs(dx)) return@awaitEachGesture
                    if (abs(dx) > slop && abs(dx) > abs(dy) * SIDEWAYS_RATIO) {
                        claimed = true
                        change.consume()
                        velocity.addPointerInputChange(change)
                        latestStart()
                    }
                } else {
                    change.consume()
                    velocity.addPointerInputChange(change)
                    latestDelta(stepX)
                }
            }
            // The pointer went away without lifting (another window took it): back where it was.
            if (claimed) latestEnd(0f)
        }
    }
}
