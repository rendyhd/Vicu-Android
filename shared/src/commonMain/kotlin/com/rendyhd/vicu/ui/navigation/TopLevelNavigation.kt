package com.rendyhd.vicu.ui.navigation

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import com.rendyhd.vicu.auth.AuthState
import kotlinx.coroutines.flow.first

/** How a move to a top-level destination treats the back stack. */
internal data class TopLevelNavOptions(
    val saveState: Boolean,
    val launchSingleTop: Boolean,
    val restoreState: Boolean,
)

internal fun isParameterizedRoute(route: Any): Boolean =
    route is ProjectRoute || route is TagRoute || route is CustomListRoute

/**
 * One policy for every way of moving to a top-level destination (the drawer, the bottom bar, a
 * widget): pop back to the Inbox, so Back from any of them leaves the app.
 *
 * - The screen being left keeps its state when it is a smart list or Settings ([leavingKeepsState]),
 *   wherever the move goes, so coming back to it shows its content in the first frame. It used to
 *   follow the destination: going to the Inbox or to a project threw Today away, and the next visit
 *   built it again from an empty list while it faded in (the cross-fade froze when the rows came).
 * - Smart lists and Settings are restored and reuse their entry.
 * - The Inbox is the root: re-created rather than restored, so re-opening it replays its loading
 *   state once instead of showing a stale saved one (issue #6).
 * - Projects, tags and custom lists share one destination type across different arguments, so
 *   they never reuse an entry or a saved state: each gets its own view model, and leaving one
 *   saves nothing (a saved state of theirs would never be restored).
 */
internal fun topLevelNavOptions(route: Any, leavingKeepsState: Boolean): TopLevelNavOptions {
    val parameterized = isParameterizedRoute(route)
    val root = route is InboxRoute
    return TopLevelNavOptions(
        saveState = leavingKeepsState,
        launchSingleTop = !parameterized,
        restoreState = !parameterized && !root,
    )
}

/** Whether [entry] is a project, tag or custom list, the destinations that never keep a saved state. */
internal fun isParameterizedEntry(entry: NavBackStackEntry): Boolean {
    val dest = entry.destination
    return dest.hasRoute(ProjectRoute::class) || dest.hasRoute(TagRoute::class) || dest.hasRoute(CustomListRoute::class)
}

/**
 * Suspends until the NavHost has given this controller its graph (the start destination is on the
 * back stack). An effect that navigates must wait for it: the NavHost is composed inside the
 * Scaffold's content, during layout, and Compose on iOS runs effects at the start of a frame,
 * before layout. A new scene (iOS rebuilds it after a long time in the background) that composes
 * the app with a widget tap waiting therefore navigated before the graph was set, and
 * `NavController.navigate` threw "You must call setGraph() before calling getGraph()".
 */
internal suspend fun NavController.awaitGraph() {
    currentBackStackEntryFlow.first()
}

internal fun NavController.navigateTopLevel(route: Any) {
    // The top-level screen this move leaves: the one just above the Inbox (none when on the Inbox).
    val leaving = currentBackStack.value
        .dropWhile { !it.destination.hasRoute(InboxRoute::class) }
        .drop(1)
        .firstOrNull()
    val options = topLevelNavOptions(route, leavingKeepsState = leaving != null && !isParameterizedEntry(leaving))
    navigate(route) {
        // Anchored on the Inbox, not on the graph's start destination: a cold start that was not
        // signed in starts on Setup, which is gone from the back stack once the user is in.
        popUpTo(InboxRoute) { saveState = options.saveState }
        launchSingleTop = options.launchSingleTop
        restoreState = options.restoreState
    }
}

/**
 * The name the drawer and the bottom bar highlight [route] by, the same string the app derives
 * from the current destination. Two routes with the same key are the same screen: choosing the
 * one already open is ignored instead of re-creating it (which reset its scroll and view model).
 */
internal fun routeKey(route: Any): String? = when (route) {
    is InboxRoute -> "InboxRoute"
    is TodayRoute -> "TodayRoute"
    is PlanRoute -> "PlanRoute"
    is UpcomingRoute -> "UpcomingRoute"
    is AnytimeRoute -> "AnytimeRoute"
    is LogbookRoute -> "LogbookRoute"
    is ReviewRoute -> "ReviewRoute"
    is RoutinesRoute -> "RoutinesRoute"
    is SettingsRoute -> "SettingsRoute"
    is AskRoute -> "AskRoute"
    is SearchRoute -> "SearchRoute"
    is SetupRoute -> "SetupRoute"
    is ProjectRoute -> "ProjectRoute/${route.projectId}"
    is TagRoute -> "TagRoute/${route.labelId}"
    is CustomListRoute -> "CustomListRoute/${route.listId}"
    else -> null
}

/** The [routeKey] of the screen [entry] shows, or null for none (or one that is not named). */
internal fun currentRouteKey(entry: NavBackStackEntry?): String? {
    val dest = entry?.destination ?: return null
    return when {
        dest.hasRoute(InboxRoute::class) -> "InboxRoute"
        dest.hasRoute(TodayRoute::class) -> "TodayRoute"
        dest.hasRoute(PlanRoute::class) -> "PlanRoute"
        dest.hasRoute(UpcomingRoute::class) -> "UpcomingRoute"
        dest.hasRoute(AnytimeRoute::class) -> "AnytimeRoute"
        dest.hasRoute(LogbookRoute::class) -> "LogbookRoute"
        dest.hasRoute(ReviewRoute::class) -> "ReviewRoute"
        dest.hasRoute(RoutinesRoute::class) -> "RoutinesRoute"
        dest.hasRoute(SettingsRoute::class) -> "SettingsRoute"
        dest.hasRoute(AskRoute::class) -> "AskRoute"
        dest.hasRoute(ProjectRoute::class) -> "ProjectRoute/${entry.toRoute<ProjectRoute>().projectId}"
        dest.hasRoute(TagRoute::class) -> "TagRoute/${entry.toRoute<TagRoute>().labelId}"
        dest.hasRoute(CustomListRoute::class) -> "CustomListRoute/${entry.toRoute<CustomListRoute>().listId}"
        else -> null
    }
}

/**
 * The first screen for [authState]: the Inbox when signed in, Setup otherwise. Starting on the
 * Inbox for everyone showed it for a frame and ran its refresh without credentials before the
 * app navigated to Setup.
 *
 * [AuthState.Loading] is not decided yet; the UI waits for it, so this is only a safe default.
 */
internal fun startDestinationFor(authState: AuthState): Any = when (authState) {
    AuthState.Authenticated, AuthState.Loading -> InboxRoute
    AuthState.Unauthenticated, AuthState.NeedsReAuth -> SetupRoute
}
