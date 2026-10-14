package com.rendyhd.vicu.ui.screens.taskdetail

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.ui.theme.VicuMotion

/** How far the editor shrinks when the back gesture is held all the way (progress 1). */
internal const val EDITOR_BACK_MIN_SCALE = 0.9f

/** How much of its opacity the editor loses at progress 1; the exit fade finishes the rest. */
internal const val EDITOR_BACK_FADE = 0.5f

/** Scale of the editor at back-gesture [progress] (0 at rest, 1 held fully). Out of range is clamped. */
internal fun editorBackScale(progress: Float): Float =
    1f - (1f - EDITOR_BACK_MIN_SCALE) * progress.coerceIn(0f, 1f)

/** Opacity of the editor at back-gesture [progress]. */
internal fun editorBackAlpha(progress: Float): Float =
    1f - EDITOR_BACK_FADE * progress.coerceIn(0f, 1f)

/**
 * The predictive back gesture of the editor: [progress] is what the finger has done (0 to 1),
 * written by the editor's `PredictiveBackHandler` and read by [EditorOverlay]'s graphics layer
 * (a draw-phase read, so a gesture frame recomposes nothing).
 */
class EditorBackState {
    var progress by mutableFloatStateOf(0f)

    /**
     * A cancelled gesture springs back to rest. It runs on the caller's coroutine, so it follows
     * the system animator scale (at scale 0 `animate` ends at the target at once).
     */
    suspend fun springBack() {
        animate(
            initialValue = progress,
            targetValue = 0f,
            animationSpec = VicuMotion.defaultSpatialSpec(),
        ) { value, _ -> progress = value }
    }
}

/**
 * The editor over the screens: fades in with a 6 dp rise (fade.base, move), fades out when
 * closed (fade.fast), and follows the back gesture ([backState]). [taskId] is the task being
 * edited, null when the editor is closed.
 *
 * Moving from one task to another (a subtask) or closing while [stayOpen] says the editor is
 * only reloading is instant: the old editor must leave composition at once because that is when
 * it saves, and the next load must not start before it did.
 */
@Composable
internal fun EditorOverlay(
    taskId: Long?,
    backState: EditorBackState,
    stayOpen: () -> Boolean,
    content: @Composable (taskId: Long, backEnabled: Boolean) -> Unit,
) {
    val risePx = with(LocalDensity.current) { VicuMotion.pageRisePx.dp.roundToPx() }
    AnimatedContent(
        targetState = taskId,
        transitionSpec = {
            val opening = initialState == null && targetState != null
            val closing = initialState != null && targetState == null && !stayOpen()
            when {
                opening -> (
                    fadeIn(VicuMotion.defaultEffectsSpec()) +
                        slideInVertically(VicuMotion.defaultSpatialSpec<IntOffset>()) { risePx }
                    ) togetherWith ExitTransition.None
                closing -> EnterTransition.None togetherWith fadeOut(VicuMotion.fastEffectsSpec())
                else -> EnterTransition.None togetherWith ExitTransition.None
            }.using(SizeTransform(clip = false) { _, _ -> snap() })
        },
        label = "taskEditor",
    ) { id ->
        if (id != null) {
            // Once the exit is over (or the editor is replaced) the next opening starts at rest.
            DisposableEffect(Unit) { onDispose { backState.progress = 0f } }
            // An editor on its way out is already saved: it takes no more touches.
            val leaving = taskId != id
            Box(
                modifier = Modifier.pointerInput(leaving) {
                    if (leaving) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        }
                    }
                }.graphicsLayer {
                    val progress = backState.progress
                    scaleX = editorBackScale(progress)
                    scaleY = editorBackScale(progress)
                    alpha = editorBackAlpha(progress)
                },
            ) {
                // Back is the editor's only while it is the one on its way in or at rest.
                content(id, taskId == id)
            }
        }
    }
}
