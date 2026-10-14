package com.rendyhd.vicu.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import com.rendyhd.vicu.ui.theme.VicuMotion

/** What a destination is, as far as the choice of transition goes. */
internal enum class TransitionScreen { Project, Search, Other }

/** The two motions between screens (design-system-v1, section 6). */
internal enum class NavTransitionKind {
    /**
     * Peers: the screen on top fades (210 ms) while the one under it stays as it is, so what both
     * screens share (the top bar) stays on screen instead of blinking. The + button is not in the
     * screens at all (AppFab).
     */
    CrossFade,

    /** Going into or out of something: both screens slide 30 dp along X and fade. */
    SharedAxisX,
}

/**
 * Into and out of Search, and into a child project and back, is an axis move; the rest are peers.
 * [pushed] is false for a project that replaces the open one (chosen in the bottom bar or the
 * drawer): that is a peer, not a step into the project that was open.
 */
internal fun navTransitionKind(
    from: TransitionScreen,
    to: TransitionScreen,
    pushed: Boolean = true,
): NavTransitionKind = when {
    from == TransitionScreen.Search || to == TransitionScreen.Search -> NavTransitionKind.SharedAxisX
    from == TransitionScreen.Project && to == TransitionScreen.Project && pushed -> NavTransitionKind.SharedAxisX
    else -> NavTransitionKind.CrossFade
}

private fun NavBackStackEntry.transitionScreen(): TransitionScreen = when {
    destination.hasRoute(ProjectRoute::class) -> TransitionScreen.Project
    destination.hasRoute(SearchRoute::class) -> TransitionScreen.Search
    else -> TransitionScreen.Other
}

/** The shared axis distance in dp; the spring is move (320 ms), the fades are the page tokens. */
internal const val SHARED_AXIS_DP = 30

/**
 * The NavHost transitions. Navigation 2.9 plays the pop pair under the predictive back gesture,
 * so a back swipe scrubs the same motion in reverse.
 *
 * The NavHost draws a screen that is navigated to above the old one and a screen that is popped
 * back to under it, which is what the cross-fade relies on: forward, the new screen fades in over
 * the old one, which is held as it is until it is covered; back, the old screen fades out over the
 * one below, which is there from the start. A fade-through (old out, then new in) blinked: for a
 * moment neither screen was there, and the top bar went with them.
 *
 * History: commit 0c762fe turned all four off because the Navigation default is a 700 ms
 * cross-fade, too slow for lists the user flips between every few seconds. What is used now is
 * short (210 ms for a cross-fade, 90 ms out and 210 ms in for an axis move) and every spec follows
 * the system animator scale, so with animations off a change is instant.
 *
 * [isOnBackStack] tells a step into a child project (the parent stays on the back stack under it)
 * from a project chosen in the bottom bar or the drawer (the open one is popped).
 *
 * With [reveal], a forward cross-fade waits for the arriving screen's content: the NavHost shows
 * that screen at once and holds the old one under it ([REVEAL_HOLD_MS]), and the screen fades
 * itself in when it is ready ([RevealedScreen]). Without it (tests) the NavHost does the fade.
 */
internal class NavTransitions(
    private val axisPx: Int,
    private val rtl: Boolean = false,
    private val isOnBackStack: (NavBackStackEntry) -> Boolean = { true },
    private val reveal: ScreenReveal? = null,
) {
    /** Forward is toward the end of the line: right in left-to-right, left in right-to-left. */
    internal fun axisOffset(forward: Boolean): Int = if (forward != rtl) axisPx else -axisPx

    private fun kind(scope: AnimatedContentTransitionScope<NavBackStackEntry>, pop: Boolean) = navTransitionKind(
        from = scope.initialState.transitionScreen(),
        to = scope.targetState.transitionScreen(),
        // Back always leaves something that was gone into (a top-level move never pops onto a
        // project); a forward move goes into something only when the old screen stays below.
        pushed = pop || isOnBackStack(scope.initialState),
    )

    private val fadeOut = fadeOut(tween(VicuMotion.pageOutMs))
    private val fadeInLate = fadeIn(tween(VicuMotion.pageInMs, delayMillis = VicuMotion.pageOutMs))

    /** The cross-fade of the screen on top: in when it arrives, out when back reveals the one below. */
    private val crossFadeIn = fadeIn(tween(VicuMotion.pageInMs))
    private val crossFadeOut = fadeOut(tween(VicuMotion.pageInMs))

    /** The old screen under an arriving one: kept as it is until it is covered, then dropped. */
    private val holdUntilCovered = fadeOut(snap(delayMillis = VicuMotion.pageInMs))

    /** The same while the arriving screen waits for its content; it stops drawing the old one itself. */
    private val holdForReveal = fadeOut(snap(delayMillis = REVEAL_HOLD_MS))

    private fun crossFadeEnter(scope: AnimatedContentTransitionScope<NavBackStackEntry>): EnterTransition {
        val reveal = reveal ?: return crossFadeIn
        reveal.arriving(scope.targetState.id)
        return EnterTransition.None
    }

    private fun crossFadeExit(scope: AnimatedContentTransitionScope<NavBackStackEntry>): ExitTransition {
        val reveal = reveal ?: return holdUntilCovered
        reveal.leaving(scope.initialState.id, coveredBy = scope.targetState.id)
        return holdForReveal
    }

    /** A screen arriving from the end side (going forward) or the start side (coming back). */
    private fun slideIn(forward: Boolean): EnterTransition = fadeInLate + slideInHorizontally(
        VicuMotion.defaultSpatialSpec<IntOffset>(),
    ) { axisOffset(forward) }

    /** A screen leaving toward the start side (going forward) or the end side (coming back). */
    private fun slideOut(forward: Boolean): ExitTransition = fadeOut + slideOutHorizontally(
        VicuMotion.defaultSpatialSpec<IntOffset>(),
    ) { -axisOffset(forward) }

    fun enter(scope: AnimatedContentTransitionScope<NavBackStackEntry>): EnterTransition =
        if (kind(scope, pop = false) == NavTransitionKind.SharedAxisX) slideIn(forward = true) else crossFadeEnter(scope)

    fun exit(scope: AnimatedContentTransitionScope<NavBackStackEntry>): ExitTransition =
        if (kind(scope, pop = false) == NavTransitionKind.SharedAxisX) slideOut(forward = true) else crossFadeExit(scope)

    fun popEnter(scope: AnimatedContentTransitionScope<NavBackStackEntry>): EnterTransition =
        if (kind(scope, pop = true) == NavTransitionKind.SharedAxisX) slideIn(forward = false) else EnterTransition.None

    fun popExit(scope: AnimatedContentTransitionScope<NavBackStackEntry>): ExitTransition =
        if (kind(scope, pop = true) == NavTransitionKind.SharedAxisX) slideOut(forward = false) else crossFadeOut
}
