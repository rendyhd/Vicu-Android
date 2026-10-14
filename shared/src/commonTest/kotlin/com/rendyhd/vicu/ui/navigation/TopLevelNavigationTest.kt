package com.rendyhd.vicu.ui.navigation

import com.rendyhd.vicu.auth.AuthState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TopLevelNavigationTest {

    @Test
    fun `smart lists and settings are restored and reuse the entry`() {
        listOf(TodayRoute, UpcomingRoute, AnytimeRoute, LogbookRoute, ReviewRoute, RoutinesRoute, SettingsRoute)
            .forEach { route ->
                assertEquals(
                    TopLevelNavOptions(saveState = true, launchSingleTop = true, restoreState = true),
                    topLevelNavOptions(route, leavingKeepsState = true),
                    route.toString(),
                )
            }
    }

    @Test
    fun `the Inbox is the root, so it is re-created rather than restored`() {
        assertEquals(
            TopLevelNavOptions(saveState = true, launchSingleTop = true, restoreState = false),
            topLevelNavOptions(InboxRoute, leavingKeepsState = true),
        )
    }

    @Test
    fun `parameterized destinations never share an entry or a saved state`() {
        listOf(ProjectRoute(3L), TagRoute(4L), CustomListRoute("x")).forEach { route ->
            assertEquals(
                TopLevelNavOptions(saveState = true, launchSingleTop = false, restoreState = false),
                topLevelNavOptions(route, leavingKeepsState = true),
                route.toString(),
            )
        }
    }

    @Test
    fun `a smart list being left keeps its state wherever the move goes, the Inbox and projects included`() {
        listOf(InboxRoute, TodayRoute, ProjectRoute(3L), CustomListRoute("x")).forEach { route ->
            assertEquals(true, topLevelNavOptions(route, leavingKeepsState = true).saveState, route.toString())
        }
    }

    @Test
    fun `a project, tag or list being left saves nothing, since it is never restored`() {
        listOf(InboxRoute, TodayRoute, ProjectRoute(3L)).forEach { route ->
            assertEquals(false, topLevelNavOptions(route, leavingKeepsState = false).saveState, route.toString())
        }
    }

    @Test
    fun `route keys match the names the drawer and the bottom bar highlight by`() {
        assertEquals("InboxRoute", routeKey(InboxRoute))
        assertEquals("TodayRoute", routeKey(TodayRoute))
        assertEquals("UpcomingRoute", routeKey(UpcomingRoute))
        assertEquals("AnytimeRoute", routeKey(AnytimeRoute))
        assertEquals("LogbookRoute", routeKey(LogbookRoute))
        assertEquals("ReviewRoute", routeKey(ReviewRoute))
        assertEquals("RoutinesRoute", routeKey(RoutinesRoute))
        assertEquals("SettingsRoute", routeKey(SettingsRoute))
        assertEquals("ProjectRoute/12", routeKey(ProjectRoute(12L)))
        assertEquals("TagRoute/5", routeKey(TagRoute(5L)))
        assertEquals("CustomListRoute/abc", routeKey(CustomListRoute("abc")))
    }

    @Test
    fun `two projects have different keys, so choosing another project is not ignored`() {
        assertNotEquals(routeKey(ProjectRoute(1L)), routeKey(ProjectRoute(2L)))
        assertEquals(routeKey(ProjectRoute(1L)), routeKey(ProjectRoute(1L)))
    }

    @Test
    fun `only a signed-in app starts on the Inbox`() {
        assertEquals(InboxRoute, startDestinationFor(AuthState.Authenticated))
        assertEquals(SetupRoute, startDestinationFor(AuthState.Unauthenticated))
        assertEquals(SetupRoute, startDestinationFor(AuthState.NeedsReAuth))
    }

    @Test
    fun `an app that has not decided yet is not sent to Setup`() {
        // The UI shows a spinner until the state is known, so this is only a safe default.
        assertEquals(InboxRoute, startDestinationFor(AuthState.Loading))
    }

    @Test
    fun `search and setup are named too`() {
        assertEquals("SearchRoute", routeKey(SearchRoute))
        assertEquals("SetupRoute", routeKey(SetupRoute))
    }
}
