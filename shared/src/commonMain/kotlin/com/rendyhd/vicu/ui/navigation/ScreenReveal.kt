package com.rendyhd.vicu.ui.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.navigation.NavBackStackEntry
import com.rendyhd.vicu.ui.theme.VicuMotion
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Longest a screen arriving by a cross-fade waits for its content before it fades in anyway. */
internal const val REVEAL_WAIT_MS = 350L

/**
 * How long the screen under an arriving one stays composed: the wait, the frame that builds the
 * content that arrived (long on a slow device), the fade, and room to spare. It stops being drawn
 * as soon as the arriving screen is fully in ([RevealedScreen]), so the length costs nothing.
 */
internal const val REVEAL_HOLD_MS = 1500

/**
 * The cross-fade between peer screens waits until the screen navigated to has something to show.
 * A screen with a fresh view model gets its content from the database a little after its first
 * frame; fading it in at once showed the empty screen, and the frame that then built the rows
 * stopped the fade half-way (both screens at half strength), after which it jumped to the end.
 *
 * [NavTransitions] tells it which screens arrive by a forward cross-fade ([arriving]) and which
 * screen covers the one they replace ([leaving]); [RevealedScreen] then fades the arriving screen in
 * once it reports itself ready ([ScreenReady]), and stops drawing the covered one once it is in.
 */
@Stable
internal class ScreenReveal {
    // Written while the NavHost works out a transition and read by the screens of that same
    // composition: plain collections, no recomposition hangs on them.
    private val arrivals = HashSet<String>()
    private val covers = HashMap<String, String>()

    /** Screens that have fully faded in, by back stack entry id. */
    private val revealed = mutableStateMapOf<String, Unit>()

    fun arriving(entry: String) {
        arrivals += entry
    }

    fun leaving(entry: String, coveredBy: String) {
        covers[entry] = coveredBy
    }

    /** Whether [entry] arrives by a cross-fade and fades itself in; asked once, by its first composition. */
    fun takeArrival(entry: String): Boolean = arrivals.remove(entry)

    fun revealedIn(entry: String) {
        revealed[entry] = Unit
    }

    /** Whether the screen that covers [entry] is fully in, so [entry] need not be drawn any more. */
    fun isCovered(entry: String): Boolean = covers[entry]?.let { it in revealed } == true

    fun forget(entry: String) {
        arrivals -= entry
        covers.remove(entry)
        revealed.remove(entry)
    }
}

/** Whether a screen has its content; screens that never say so count as ready. */
@Stable
internal class ScreenReadiness {
    var ready by mutableStateOf(true)
}

internal val LocalScreenReadiness = staticCompositionLocalOf<ScreenReadiness?> { null }

/**
 * Tells the navigation whether this screen has its content yet (false while its first data is
 * loading). A screen arriving by a cross-fade waits for it, at most [REVEAL_WAIT_MS], before fading in.
 */
@Composable
fun ScreenReady(ready: Boolean) {
    val readiness = LocalScreenReadiness.current ?: return
    SideEffect { readiness.ready = ready }
}

/**
 * A destination's content as [ScreenReveal] shows it: an arriving screen fades itself in once it is
 * ready, and a screen being covered stops being drawn once the screen over it is fully in. Every
 * other screen is drawn as the NavHost transitions say.
 */
@Composable
internal fun AnimatedVisibilityScope.RevealedScreen(
    entry: NavBackStackEntry,
    reveal: ScreenReveal,
    content: @Composable () -> Unit,
) {
    val readiness = remember { ScreenReadiness() }
    val fadesIn = remember { reveal.takeArrival(entry.id) }
    val alpha = remember { Animatable(if (fadesIn) 0f else 1f) }
    if (fadesIn) {
        LaunchedEffect(Unit) {
            withTimeoutOrNull(REVEAL_WAIT_MS) { snapshotFlow { readiness.ready }.first { it } }
            // The content that just arrived is built in a frame of its own; the fade starts after it.
            withFrameNanos { }
            alpha.animateTo(1f, tween(VicuMotion.pageInMs))
            reveal.revealedIn(entry.id)
        }
    }
    DisposableEffect(entry.id) { onDispose { reveal.forget(entry.id) } }
    val transition = transition
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val coveredNow = transition.targetState == EnterExitState.PostExit && reveal.isCovered(entry.id)
                this.alpha = if (coveredNow) 0f else alpha.value
            },
    ) {
        CompositionLocalProvider(LocalScreenReadiness provides readiness) { content() }
    }
}
