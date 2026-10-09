package com.rendyhd.vicu.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
    /** Peers: the old screen fades out (90 ms), the new one fades in (210 ms), no scale. */
    FadeThrough,

    /** Going into or out of something: both screens slide 30 dp along X and fade. */
    SharedAxisX,
}

/** Into and out of a project (a child project is pushed on its parent) and of Search is an axis move; the rest are peers. */
internal fun navTransitionKind(from: TransitionScreen, to: TransitionScreen): NavTransitionKind = when {
    from == TransitionScreen.Project && to == TransitionScreen.Project -> NavTransitionKind.SharedAxisX
    from == TransitionScreen.Search || to == TransitionScreen.Search -> NavTransitionKind.SharedAxisX
    else -> NavTransitionKind.FadeThrough
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
 * History: commit 0c762fe turned all four off because the Navigation default is a 700 ms
 * cross-fade, too slow for lists the user flips between every few seconds. What is used now is
 * short (90 ms out, 210 ms in, 300 ms in total) and every spec follows the system animator
 * scale, so with animations off a change is instant, as it has been since that commit.
 */
internal class NavTransitions(private val axisPx: Int, private val rtl: Boolean = false) {
    /** Forward is toward the end of the line: right in left-to-right, left in right-to-left. */
    internal fun axisOffset(forward: Boolean): Int = if (forward != rtl) axisPx else -axisPx

    private fun kind(scope: AnimatedContentTransitionScope<NavBackStackEntry>) = navTransitionKind(
        from = scope.initialState.transitionScreen(),
        to = scope.targetState.transitionScreen(),
    )

    private val fadeOut = fadeOut(tween(VicuMotion.pageOutMs))
    private val fadeInLate = fadeIn(tween(VicuMotion.pageInMs, delayMillis = VicuMotion.pageOutMs))

    /** Fade only: the new screen does not grow in, a zoom between tabs was disorienting. */
    private fun enterFadeThrough(): EnterTransition = fadeInLate

    /** A screen arriving from the end side (going forward) or the start side (coming back). */
    private fun slideIn(forward: Boolean): EnterTransition = fadeInLate + slideInHorizontally(
        VicuMotion.defaultSpatialSpec<IntOffset>(),
    ) { axisOffset(forward) }

    /** A screen leaving toward the start side (going forward) or the end side (coming back). */
    private fun slideOut(forward: Boolean): ExitTransition = fadeOut + slideOutHorizontally(
        VicuMotion.defaultSpatialSpec<IntOffset>(),
    ) { -axisOffset(forward) }

    fun enter(scope: AnimatedContentTransitionScope<NavBackStackEntry>): EnterTransition =
        if (kind(scope) == NavTransitionKind.SharedAxisX) slideIn(forward = true) else enterFadeThrough()

    fun exit(scope: AnimatedContentTransitionScope<NavBackStackEntry>): ExitTransition =
        if (kind(scope) == NavTransitionKind.SharedAxisX) slideOut(forward = true) else fadeOut

    fun popEnter(scope: AnimatedContentTransitionScope<NavBackStackEntry>): EnterTransition =
        if (kind(scope) == NavTransitionKind.SharedAxisX) slideIn(forward = false) else enterFadeThrough()

    fun popExit(scope: AnimatedContentTransitionScope<NavBackStackEntry>): ExitTransition =
        if (kind(scope) == NavTransitionKind.SharedAxisX) slideOut(forward = false) else fadeOut
}
