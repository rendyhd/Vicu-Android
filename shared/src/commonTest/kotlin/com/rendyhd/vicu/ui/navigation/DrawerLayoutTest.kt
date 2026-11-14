package com.rendyhd.vicu.ui.navigation

import com.rendyhd.vicu.data.local.DrawerView
import com.rendyhd.vicu.domain.model.BottomBarSlot
import com.rendyhd.vicu.domain.model.BottomBarSlotType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** How the drawer is arranged: its order, and the views of the bottom bar that are in it too. */
class DrawerLayoutTest {

    private fun view(view: DrawerView): DrawerEntry = DrawerEntry.View(view)
    private fun block(block: DrawerBlock): DrawerEntry = DrawerEntry.Block(block)

    private val offers = drawerViewOffers(BottomBarSlot.DEFAULT_SLOTS, routinesEnabled = true, reviewEnabled = true, askReady = false)

    private fun shownKeys(
        stored: List<String> = emptyList(),
        hidden: Set<DrawerView> = emptySet(),
        barViews: Set<DrawerView> = emptySet(),
    ): List<String> = DrawerLayout.shown(stored, offers, hidden, barViews).map { it.key }

    @Test
    fun `nothing moves until the user changes it`() {
        // The bar's views stay out, the others come first, then the projects, lists and labels.
        assertEquals(listOf("LOGBOOK", "ROUTINES", "REVIEW", "PROJECTS", "LISTS", "LABELS"), shownKeys())
    }

    @Test
    fun `the Inbox and the bar's smart lists are offered - marked as in the bar`() {
        val inBar = offers.filter { it.inBar }.map { it.view }

        assertEquals(listOf(DrawerView.INBOX, DrawerView.TODAY, DrawerView.UPCOMING, DrawerView.ANYTIME), inBar)
        assertFalse(offers.first { it.view == DrawerView.LOGBOOK }.inBar)
    }

    @Test
    fun `a view of the bar is in the drawer once it is turned on`() {
        assertEquals(
            listOf("TODAY", "UPCOMING", "LOGBOOK", "ROUTINES", "REVIEW", "PROJECTS", "LISTS", "LABELS"),
            shownKeys(barViews = setOf(DrawerView.TODAY, DrawerView.UPCOMING)),
        )
    }

    @Test
    fun `hiding and the bar's switch are kept apart`() {
        // Anytime was hidden while the bar did not hold it. In the bar it is out until turned on,
        // whatever the hidden set says; out of the bar again, hidden still stands.
        val inBar = DrawerViewOffer(DrawerView.ANYTIME, inBar = true)
        val notInBar = DrawerViewOffer(DrawerView.ANYTIME, inBar = false)
        val hidden = setOf(DrawerView.ANYTIME)

        assertFalse(DrawerLayout.isShown(inBar, hidden, emptySet()))
        assertTrue(DrawerLayout.isShown(inBar, hidden, setOf(DrawerView.ANYTIME)))
        assertFalse(DrawerLayout.isShown(notInBar, hidden, setOf(DrawerView.ANYTIME)))
        assertTrue(DrawerLayout.isShown(notInBar, emptySet(), emptySet()))
    }

    @Test
    fun `views can go below the projects`() {
        val stored = listOf("PROJECTS", "LISTS", "ROUTINES", "REVIEW", "LOGBOOK", "LABELS")

        assertEquals(listOf("PROJECTS", "LISTS", "ROUTINES", "REVIEW", "LOGBOOK", "LABELS"), shownKeys(stored))
    }

    @Test
    fun `an entry the stored order does not name follows the one before it in the default order`() {
        // Stored before Plan and Ask existed for this user, with Routines moved under the projects.
        val order = DrawerLayout.order(listOf("TODAY", "UPCOMING", "ANYTIME", "LOGBOOK", "REVIEW", "PROJECTS", "ROUTINES", "LISTS", "LABELS"))
        val keys = order.map { it.key }

        assertEquals(DrawerLayout.DEFAULT.size, keys.size, "every entry has a place")
        assertEquals("INBOX", keys.first(), "nothing comes before the Inbox by default, so it leads")
        assertEquals(keys.indexOf("TODAY") + 1, keys.indexOf("PLAN"))
        assertEquals(keys.indexOf("REVIEW") + 1, keys.indexOf("ASK"))
        assertTrue(keys.indexOf("ROUTINES") > keys.indexOf("PROJECTS"), "what the user moved stays where it was put")
    }

    @Test
    fun `a key from a newer version and a repeated key are left out`() {
        val order = DrawerLayout.order(listOf("CALENDAR", "PROJECTS", "PROJECTS", "LOGBOOK"))

        assertEquals(DrawerLayout.DEFAULT.toSet(), order.toSet())
        assertEquals(DrawerLayout.DEFAULT.size, order.size)
    }

    @Test
    fun `Settings lists every offered view - shown or not - and the blocks`() {
        val arrangeable = DrawerLayout.arrangeable(emptyList(), offers).map { it.key }

        assertEquals(
            listOf("INBOX", "TODAY", "UPCOMING", "ANYTIME", "LOGBOOK", "ROUTINES", "REVIEW", "PROJECTS", "LISTS", "LABELS"),
            arrangeable,
        )
    }

    @Test
    fun `arranging keeps the place of an entry that is not offered now`() {
        // Plan (planner off) and Ask (not set up) are not in the Settings list. Routines is dragged
        // under the lists; Plan stays after Today and Ask after Review.
        val arrangeable = DrawerLayout.arrangeable(emptyList(), offers)
        val from = arrangeable.indexOf(view(DrawerView.ROUTINES))
        val to = arrangeable.indexOf(block(DrawerBlock.LISTS))

        val keys = DrawerLayout.keysAfterArranging(emptyList(), DrawerLayout.moved(arrangeable, from, to))

        assertEquals(
            listOf(
                "INBOX", "TODAY", "PLAN", "UPCOMING", "ANYTIME", "LOGBOOK", "REVIEW", "PROJECTS", "ASK",
                "FLAGGED", "WAITING_ON", "PEOPLE", "LISTS", "ROUTINES", "LABELS",
            ),
            keys,
        )
        assertEquals(DrawerLayout.DEFAULT.map { it.key }.sorted(), keys.sorted(), "every entry is stored once")
        assertEquals(listOf("LOGBOOK", "REVIEW", "PROJECTS", "LISTS", "ROUTINES", "LABELS"), shownKeys(keys))
    }

    @Test
    fun `a move out of range changes nothing`() {
        val list = listOf("a", "b", "c")

        assertEquals(list, DrawerLayout.moved(list, 0, 3))
        assertEquals(list, DrawerLayout.moved(list, 1, 1))
        assertEquals(listOf("b", "c", "a"), DrawerLayout.moved(list, 0, 2))
    }

    @Test
    fun `Plan is offered while the planner is on - in the bar when a slot holds it`() {
        val slots = listOf(BottomBarSlot(BottomBarSlotType.PLAN), BottomBarSlot(BottomBarSlotType.TODAY))

        val on = drawerViewOffers(slots, routinesEnabled = false, reviewEnabled = false, askReady = false, plannerEnabled = true)
        val off = drawerViewOffers(slots, routinesEnabled = false, reviewEnabled = false, askReady = false, plannerEnabled = false)

        assertTrue(on.first { it.view == DrawerView.PLAN }.inBar)
        assertTrue(off.none { it.view == DrawerView.PLAN })
    }
}
