package com.rendyhd.vicu.ui.components.shared

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry
import com.rendyhd.vicu.ui.components.fab.FabController
import com.rendyhd.vicu.ui.components.fab.fabGestures
import com.rendyhd.vicu.ui.theme.VicuMotion
import com.rendyhd.vicu.voice.TaskDefaults

/** What a screen's + button says and does; [taskDefaults] is null when it adds something else than a task. */
@Immutable
internal data class FabSpec(
    val label: String,
    val expanded: Boolean,
    val onClick: () -> Unit,
    val taskDefaults: TaskDefaults? = TaskDefaults(),
)

/**
 * The buttons the screens put in with [ScreenFab], by back stack entry id. The app draws one + button
 * ([AppFab]), the one of the screen on top, outside the screens: a screen change fades the screens
 * under it and leaves the button where it is. With a button in every screen, every change of screen
 * faded it out and in again.
 */
@Stable
class AppFabHost {
    private val specs = mutableStateMapOf<String, FabSpec>()

    internal fun put(screen: String, spec: FabSpec) {
        // An equal spec is not written again, so a screen's recomposition does not recompose the button.
        if (specs[screen] != spec) specs[screen] = spec
    }

    internal fun remove(screen: String) {
        specs.remove(screen)
    }

    internal fun specOf(screen: String?): FabSpec? = screen?.let { specs[it] }
}

/** The app's [AppFabHost]; null outside the app (a screen then draws its own button). */
val LocalAppFabHost = staticCompositionLocalOf<AppFabHost?> { null }

/** The FAB's height: what [ScreenFab] keeps free in the screen's Scaffold. */
private val FAB_HEIGHT = 56.dp

/**
 * A screen's + button, in its Scaffold's `floatingActionButton` slot. The button itself is drawn by
 * [AppFab]; this hands it [onClick], [label] and [expanded] while it is in the composition (leave it
 * out, for a selection, to hide the button) and keeps the button's place free, so the Scaffold still
 * lifts the screen's snackbar above it. [taskDefaults] is what a task said while holding the button
 * gets, the same as the screen's quick add; null for a button that adds something else (a routine),
 * which then only taps.
 *
 * The screen is told apart by its back stack entry, which the NavHost provides as the lifecycle owner
 * of each destination. Outside the app (no host, no entry) it draws its own [VicuFab].
 */
@Composable
fun ScreenFab(
    onClick: () -> Unit,
    expanded: Boolean = true,
    label: String = "New task",
    taskDefaults: TaskDefaults? = TaskDefaults(),
) {
    val host = LocalAppFabHost.current
    val screen = (LocalLifecycleOwner.current as? NavBackStackEntry)?.id
    if (host == null || screen == null) {
        VicuFab(onClick = onClick, label = label, expanded = expanded)
        return
    }
    val currentOnClick by rememberUpdatedState(onClick)
    val click = remember { { currentOnClick() } }
    val spec = FabSpec(label, expanded, click, taskDefaults)
    SideEffect { host.put(screen, spec) }
    DisposableEffect(host, screen) { onDispose { host.remove(screen) } }
    Spacer(Modifier.size(FAB_HEIGHT))
}

/** Frames a new screen has to put its button in before it counts as a screen without one. */
internal const val FAB_SETTLE_FRAMES = 3

/**
 * The button to draw: the [current] screen's, or while that screen has not put one in yet
 * ([settling], its first frames) the [last] one shown, so the button does not drop out for a frame
 * between two screens that both have one. Null hides it.
 */
internal fun fabToShow(current: FabSpec?, last: FabSpec?, settling: Boolean): FabSpec? =
    current ?: if (settling) last else null

/**
 * The app's + button: the one [screen] (the back stack entry on top) put in with [ScreenFab]. It
 * fades and scales in and out when a screen without one comes and goes, or a selection starts.
 * With a [controller] it can also be held to say a task and dragged onto a header (`fabGestures`);
 * a tap stays the screen's own. [compact] draws it without its label, whatever the screen asked
 * for: an app message is beside it and needs the room.
 */
@Composable
fun AppFab(
    host: AppFabHost,
    screen: String?,
    modifier: Modifier = Modifier,
    controller: FabController? = null,
    compact: Boolean = false,
) {
    // A screen puts its button in after its first frame; until a few frames have passed, a screen
    // that has not done so yet keeps the previous button instead of hiding it.
    var settledScreen by remember { mutableStateOf(screen) }
    LaunchedEffect(screen) {
        repeat(FAB_SETTLE_FRAMES) { withFrameNanos { } }
        settledScreen = screen
    }
    val current = host.specOf(screen)
    // The last button drawn: shown while settling, and while the button animates out.
    val last = remember { arrayOfNulls<FabSpec>(1) }
    val spec = fabToShow(current, last[0], settling = settledScreen != screen)
    if (spec != null) last[0] = spec

    AnimatedVisibility(
        visible = spec != null,
        modifier = modifier,
        enter = fadeIn(VicuMotion.fastEffectsSpec()) + scaleIn(VicuMotion.defaultSpatialSpec(), initialScale = 0.8f),
        exit = fadeOut(VicuMotion.fastEffectsSpec()) + scaleOut(VicuMotion.fastEffectsSpec(), targetScale = 0.8f),
    ) {
        val shown = spec ?: last[0] ?: return@AnimatedVisibility
        val defaults = shown.taskDefaults
        val expanded = shown.expanded && !compact
        if (controller == null || defaults == null) {
            VicuFab(onClick = shown.onClick, label = shown.label, expanded = expanded)
        } else {
            val tap = {
                controller.onTapped()
                shown.onClick()
            }
            VicuFab(
                onClick = tap,
                label = shown.label,
                expanded = expanded,
                modifier = Modifier.fabGestures(controller, screen, defaults, openQuickAdd = tap),
            )
        }
    }
}
