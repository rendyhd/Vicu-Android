package com.rendyhd.vicu.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ViewTargetTest {

    private val all = listOf(
        ViewTarget.Today,
        ViewTarget.Inbox,
        ViewTarget.Upcoming,
        ViewTarget.Anytime,
        ViewTarget.Routines,
        ViewTarget.Project(12L),
        ViewTarget.CustomList("c0ffee-01"),
    )

    @Test
    fun `the wire names are the ones widgets and pending notifications already carry`() {
        // Intents built by an older version can still be delivered: these strings are frozen.
        assertEquals("TODAY", ViewTarget.Today.typeName)
        assertEquals("INBOX", ViewTarget.Inbox.typeName)
        assertEquals("UPCOMING", ViewTarget.Upcoming.typeName)
        assertEquals("ANYTIME", ViewTarget.Anytime.typeName)
        assertEquals("ROUTINES", ViewTarget.Routines.typeName)
        assertEquals("PROJECT", ViewTarget.Project(1).typeName)
        assertEquals("CUSTOM_LIST", ViewTarget.CustomList("x").typeName)
        assertEquals("navigate_to_view_type", ViewTarget.EXTRA_TYPE)
        assertEquals("navigate_to_view_id", ViewTarget.EXTRA_ID)
    }

    @Test
    fun `every target survives its wire form`() {
        all.forEach { assertEquals(it, ViewTarget.parse(it.typeName, it.idOrEmpty), it.toString()) }
    }

    @Test
    fun `targets without an id carry an empty one`() {
        assertEquals("", ViewTarget.Today.idOrEmpty)
        assertEquals("12", ViewTarget.Project(12L).idOrEmpty)
        assertEquals("c0ffee-01", ViewTarget.CustomList("c0ffee-01").idOrEmpty)
    }

    @Test
    fun `smart lists ignore any id that comes with them`() {
        assertEquals(ViewTarget.Today, ViewTarget.parse("TODAY", "garbage"))
        assertEquals(ViewTarget.Routines, ViewTarget.parse("ROUTINES", null))
    }

    @Test
    fun `a project or list target without a usable id is no target`() {
        assertNull(ViewTarget.parse("PROJECT", null))
        assertNull(ViewTarget.parse("PROJECT", ""))
        assertNull(ViewTarget.parse("PROJECT", "abc"))
        assertNull(ViewTarget.parse("CUSTOM_LIST", null))
        assertNull(ViewTarget.parse("CUSTOM_LIST", "   "))
    }

    @Test
    fun `an unknown or missing type is no target`() {
        assertNull(ViewTarget.parse(null, null))
        assertNull(ViewTarget.parse("", ""))
        assertNull(ViewTarget.parse("today", ""))
        assertNull(ViewTarget.parse("LOGBOOK", ""))
    }

    @Test
    fun `each target opens its own route`() {
        assertEquals(TodayRoute, ViewTarget.Today.toRoute())
        assertEquals(InboxRoute, ViewTarget.Inbox.toRoute())
        assertEquals(UpcomingRoute, ViewTarget.Upcoming.toRoute())
        assertEquals(AnytimeRoute, ViewTarget.Anytime.toRoute())
        assertEquals(RoutinesRoute, ViewTarget.Routines.toRoute())
        assertEquals(ProjectRoute(12L), ViewTarget.Project(12L).toRoute())
        assertEquals(CustomListRoute("c0ffee-01"), ViewTarget.CustomList("c0ffee-01").toRoute())
    }
}
