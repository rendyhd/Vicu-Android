package com.rendyhd.vicu.ui.components.shared

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.ui.theme.VicuMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

private val ThumbWidth = 4.dp
private val ThumbDraggedWidth = 8.dp
private val ThumbMinHeight = 32.dp
private val ThumbEdgeGap = 2.dp
private val TargetSize = 48.dp
private const val THUMB_ALPHA = 0.6f
private const val HIDE_AFTER_MILLIS = 1000L

/**
 * The scrollbar of a list: nothing at rest, a thin thumb at the end edge while the list scrolls, and
 * gone a second after it stops. While it shows, the thumb has a 48 dp target that drags the list.
 * Only the thumb takes touches and only it is kept from the system's Back swipe; the rest of the edge
 * stays Back, and rows under it keep their own gestures. Put it on the LazyColumn's modifier, before
 * any padding so the thumb sits at the edge.
 */
@Composable
fun Modifier.listScrollbar(state: LazyListState): Modifier {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current
    val minThumb = with(density) { ThumbMinHeight.toPx() }
    val targetSize = with(density) { TargetSize.toPx() }
    val edgeGap = with(density) { ThumbEdgeGap.toPx() }
    val exclude = rememberEdgeGestureExclusion()
    var dragging by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val visibility = remember { Animatable(0f) }
    val width by animateDpAsState(if (dragging) ThumbDraggedWidth else ThumbWidth, VicuMotion.fastSpatialSpec())

    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress || dragging }.collectLatest { active ->
            if (active) {
                visibility.animateTo(1f, VicuMotion.fastEffectsSpec())
            } else {
                delay(HIDE_AFTER_MILLIS)
                visibility.animateTo(0f, VicuMotion.defaultEffectsSpec())
            }
        }
    }
    LaunchedEffect(state) {
        snapshotFlow {
            val area = bounds
            val thumb = if (visibility.value > 0f && area != null) state.scrollbarThumb(minThumb) else null
            if (thumb == null || area == null) {
                null
            } else {
                thumbTarget(thumb, area.width, targetSize).translate(area.topLeft)
            }
        }.distinctUntilChanged().collect(exclude)
    }

    return this
        .onGloballyPositioned { bounds = it.boundsInRoot() }
        .pointerInput(state) {
            // The Initial pass: a press on the thumb reaches neither the list nor its rows.
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (visibility.value <= 0f) return@awaitEachGesture
                val thumb = state.scrollbarThumb(minThumb) ?: return@awaitEachGesture
                if (!thumbTarget(thumb, size.width.toFloat(), targetSize).contains(down.position)) return@awaitEachGesture
                down.consume()
                dragging = true
                val grab = down.position.y - thumb.offset
                try {
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes
                            .firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) break
                        val current = state.scrollbarThumb(minThumb) ?: break
                        val target = scrollbarTarget(
                            thumbOffset = change.position.y - grab,
                            thumbSize = current.size,
                            averageItem = state.averageItemSize(),
                            totalItems = state.layoutInfo.totalItemsCount,
                            viewport = state.viewportHeight(),
                        )
                        state.requestScrollToItem(target.index, target.offset)
                    }
                } finally {
                    dragging = false
                }
            }
        }
        .drawWithContent {
            drawContent()
            val alpha = visibility.value
            if (alpha <= 0f) return@drawWithContent
            val thumb = state.scrollbarThumb(minThumb) ?: return@drawWithContent
            val thumbWidth = width.toPx()
            drawRoundRect(
                color = color,
                alpha = alpha * THUMB_ALPHA,
                topLeft = Offset(size.width - thumbWidth - edgeGap, thumb.offset),
                size = Size(thumbWidth, thumb.size),
                cornerRadius = CornerRadius(thumbWidth / 2),
            )
        }
}

private fun LazyListState.viewportHeight(): Float =
    layoutInfo.viewportSize.height.toFloat()

private fun LazyListState.averageItemSize(): Float {
    val visible = layoutInfo.visibleItemsInfo
    return if (visible.isEmpty()) 0f else visible.sumOf { it.size }.toFloat() / visible.size
}

private fun LazyListState.scrollbarThumb(minThumb: Float): ScrollbarThumb? = scrollbarThumb(
    firstIndex = firstVisibleItemIndex,
    firstOffset = firstVisibleItemScrollOffset,
    averageItem = averageItemSize(),
    totalItems = layoutInfo.totalItemsCount,
    viewport = viewportHeight(),
    minThumb = minThumb,
    atStart = !canScrollBackward,
    atEnd = !canScrollForward,
)

private fun thumbTarget(thumb: ScrollbarThumb, listWidth: Float, targetSize: Float): Rect {
    val height = maxOf(thumb.size, targetSize)
    val top = thumb.offset + thumb.size / 2 - height / 2
    return Rect(listWidth - targetSize, top, listWidth, top + height)
}

internal data class ScrollbarThumb(val offset: Float, val size: Float)

internal data class ScrollbarTarget(val index: Int, val offset: Int)

/**
 * Where the thumb goes, or null when the list has nothing to scroll. Rows differ in height, so the
 * position is estimated from the number of rows and the average height of those on screen; the ends
 * are exact, because the list says when it is at one.
 */
internal fun scrollbarThumb(
    firstIndex: Int,
    firstOffset: Int,
    averageItem: Float,
    totalItems: Int,
    viewport: Float,
    minThumb: Float,
    atStart: Boolean,
    atEnd: Boolean,
): ScrollbarThumb? {
    if (atStart && atEnd) return null
    if (averageItem <= 0f || totalItems <= 0 || viewport <= 0f) return null
    val content = maxOf(averageItem * totalItems, viewport + 1f)
    val size = (viewport * viewport / content).coerceIn(minOf(minThumb, viewport), viewport)
    val fraction = when {
        atStart -> 0f
        atEnd -> 1f
        else -> ((firstIndex * averageItem + firstOffset) / (content - viewport)).coerceIn(0f, 1f)
    }
    return ScrollbarThumb(offset = fraction * (viewport - size), size = size)
}

/** The inverse: the row and offset a thumb dragged to [thumbOffset] asks for. */
internal fun scrollbarTarget(
    thumbOffset: Float,
    thumbSize: Float,
    averageItem: Float,
    totalItems: Int,
    viewport: Float,
): ScrollbarTarget {
    if (averageItem <= 0f || totalItems <= 0) return ScrollbarTarget(0, 0)
    val travel = (viewport - thumbSize).coerceAtLeast(1f)
    val fraction = (thumbOffset / travel).coerceIn(0f, 1f)
    val content = maxOf(averageItem * totalItems, viewport + 1f)
    val scrolled = fraction * (content - viewport)
    val index = (scrolled / averageItem).toInt().coerceAtMost(totalItems - 1)
    return ScrollbarTarget(index, (scrolled - index * averageItem).toInt())
}

/**
 * Keeps the system's edge swipe away from a rectangle in the window, while it is set. Returns the
 * setter; null lifts the exclusion. It goes when the caller leaves composition.
 */
@Composable
internal expect fun rememberEdgeGestureExclusion(): (Rect?) -> Unit
