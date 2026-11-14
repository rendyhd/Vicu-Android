package com.rendyhd.vicu.ui.navigation

import androidx.navigationevent.NavigationEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A Back swipe from the left edge of a list screen opens the drawer; every other Back is passed on. */
class EdgeDrawerTest {

    private val did = mutableListOf<String>()
    private var drawerOpen = false
    private val gesture = EdgeBackGesture(
        drawerOpen = { drawerOpen },
        open = { did += "open" },
        close = { did += "close" },
        passOn = { did += "pass" },
    )

    @Test
    fun `a swipe from the left edge opens the drawer as it starts and stays open when it completes`() {
        gesture.started(NavigationEvent.EDGE_LEFT)
        assertEquals(listOf("open"), did)
        assertTrue(gesture.inGesture)

        gesture.completed()
        assertEquals(listOf("open"), did, "the gesture is the drawer's: nothing goes back")
        assertFalse(gesture.inGesture)
    }

    @Test
    fun `taking the finger back to the left edge closes the drawer again`() {
        gesture.started(NavigationEvent.EDGE_LEFT)
        gesture.cancelled()

        assertEquals(listOf("open", "close"), did)
        assertFalse(gesture.inGesture)
    }

    @Test
    fun `a swipe from the right edge is Back as before`() {
        gesture.started(NavigationEvent.EDGE_RIGHT)
        assertTrue(did.isEmpty(), "nothing moves while it is held")

        gesture.completed()
        assertEquals(listOf("pass"), did)
    }

    @Test
    fun `a cancelled swipe from the right edge does nothing`() {
        gesture.started(NavigationEvent.EDGE_RIGHT)
        gesture.cancelled()

        assertTrue(did.isEmpty())
    }

    @Test
    fun `Back without a gesture is passed on`() {
        // The Back button, or a system that does not say where a swipe started (before Android 14).
        gesture.completed()

        assertEquals(listOf("pass"), did)
    }

    @Test
    fun `while the drawer is open Back closes it - from either edge or a button`() {
        drawerOpen = true
        gesture.started(NavigationEvent.EDGE_LEFT)
        assertTrue(did.isEmpty(), "nothing moves while it is held")
        gesture.completed()
        assertEquals(listOf("close"), did)

        did.clear()
        gesture.started(NavigationEvent.EDGE_RIGHT)
        gesture.completed()
        assertEquals(listOf("close"), did)

        did.clear()
        gesture.completed()
        assertEquals(listOf("close"), did, "the Back button")
    }

    @Test
    fun `a cancelled Back leaves the open drawer open`() {
        drawerOpen = true
        gesture.started(NavigationEvent.EDGE_RIGHT)
        gesture.cancelled()

        assertTrue(did.isEmpty())
    }

    @Test
    fun `the swipe that opened the drawer does not close it when the drawer has settled meanwhile`() {
        gesture.started(NavigationEvent.EDGE_LEFT)
        drawerOpen = true
        gesture.completed()

        assertEquals(listOf("open"), did)
    }

    @Test
    fun `one gesture does not colour the next`() {
        gesture.started(NavigationEvent.EDGE_LEFT)
        gesture.completed()
        gesture.completed()

        assertEquals(listOf("open", "pass"), did)
    }

    @Test
    fun `the lists open the drawer from the edge - Plan Settings and Search do not`() {
        for (key in listOf("InboxRoute", "TodayRoute", "UpcomingRoute", "AnytimeRoute", "LogbookRoute", "RoutinesRoute", "ProjectRoute/4", "TagRoute/2", "CustomListRoute/a")) {
            assertTrue(isEdgeDrawerRoute(key), key)
        }
        for (key in listOf("PlanRoute", "SettingsRoute", "SearchRoute", "AskRoute", "SetupRoute", null)) {
            assertFalse(isEdgeDrawerRoute(key), key.toString())
        }
    }
}
