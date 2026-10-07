package com.rendyhd.vicu.ui

import com.rendyhd.vicu.domain.model.BottomBarSlot
import com.rendyhd.vicu.domain.model.BottomBarSlotType
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.CustomListFilter
import com.rendyhd.vicu.domain.model.Project
import kotlin.test.Test
import kotlin.test.assertEquals

/** The bottom bar: the Inbox, then the slots the user chose (UI-28). */
class BottomBarItemsTest {

    private val inbox = Project(id = 5, title = "Inbox")
    private val work = Project(id = 7, title = "Work")
    private val list = CustomList(id = "l1", name = "Focus", filter = CustomListFilter())

    private fun items(slots: List<BottomBarSlot>) =
        resolveBottomBarItems(slots, listOf(inbox, work), listOf(list), inboxProjectId = 5)

    private fun names(slots: List<BottomBarSlot>) = items(slots).map { it.routeName }

    @Test
    fun `the inbox is always the first item`() {
        assertEquals(listOf("InboxRoute"), names(emptyList()))
    }

    @Test
    fun `the default slots follow the inbox`() {
        assertEquals(
            listOf("InboxRoute", "TodayRoute", "UpcomingRoute", "AnytimeRoute"),
            names(BottomBarSlot.DEFAULT_SLOTS),
        )
    }

    @Test
    fun `a project slot is named after its project`() {
        val result = items(listOf(BottomBarSlot(BottomBarSlotType.PROJECT, "7")))

        assertEquals("Work", result.last().label)
        assertEquals("ProjectRoute/7", result.last().routeName)
    }

    @Test
    fun `a custom list slot is named after its list`() {
        val result = items(listOf(BottomBarSlot(BottomBarSlotType.CUSTOM_LIST, "l1")))

        assertEquals("Focus", result.last().label)
        assertEquals("CustomListRoute/l1", result.last().routeName)
    }

    @Test
    fun `a slot for something that is gone is left out`() {
        assertEquals(
            listOf("InboxRoute"),
            names(
                listOf(
                    BottomBarSlot(BottomBarSlotType.PROJECT, "99"),
                    BottomBarSlot(BottomBarSlotType.PROJECT, "not a number"),
                    BottomBarSlot(BottomBarSlotType.CUSTOM_LIST, "gone"),
                ),
            ),
        )
    }

    @Test
    fun `a project slot for the inbox does not add the inbox twice`() {
        assertEquals(listOf("InboxRoute"), names(listOf(BottomBarSlot(BottomBarSlotType.PROJECT, "5"))))
    }
}
