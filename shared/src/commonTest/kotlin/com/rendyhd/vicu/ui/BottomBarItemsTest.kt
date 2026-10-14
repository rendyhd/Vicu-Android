package com.rendyhd.vicu.ui

import com.rendyhd.vicu.domain.model.BottomBarSlot
import com.rendyhd.vicu.domain.model.BottomBarSlotType
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.CustomListFilter
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.ui.components.shared.SmartListIdentity
import com.rendyhd.vicu.util.parseHexColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The bottom bar: the Inbox, then the slots the user chose (UI-28). */
class BottomBarItemsTest {

    private val inbox = Project(id = 5, title = "Inbox")
    private val work = Project(id = 7, title = "Work")
    private val list = CustomList(id = "l1", name = "Focus", filter = CustomListFilter())

    private fun items(slots: List<BottomBarSlot>, smartListColors: Boolean = true) =
        resolveBottomBarItems(slots, listOf(inbox, work), listOf(list), inboxProjectId = 5, smartListColors = smartListColors)

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

    @Test
    fun `the smart lists wear their identity colours`() {
        assertEquals(
            listOf(SmartListIdentity.INBOX, SmartListIdentity.TODAY, SmartListIdentity.UPCOMING, SmartListIdentity.ANYTIME),
            items(BottomBarSlot.DEFAULT_SLOTS).map { it.identity },
        )
    }

    @Test
    fun `with smart list colours off no icon is coloured, the inbox included`() {
        val result = items(BottomBarSlot.DEFAULT_SLOTS, smartListColors = false)

        assertEquals(4, result.size)
        result.forEach {
            assertNull(it.identity, it.routeName)
            assertNull(it.color, it.routeName)
        }
    }

    @Test
    fun `a project or list slot wears the colour chosen for it, whatever the smart list setting`() {
        val slots = listOf(
            BottomBarSlot(BottomBarSlotType.PROJECT, "7", colorHex = "#4186e0"),
            BottomBarSlot(BottomBarSlotType.CUSTOM_LIST, "l1", colorHex = "#e8384f"),
        )
        for (smartListColors in listOf(true, false)) {
            val result = items(slots, smartListColors).drop(1)
            assertEquals(listOf(parseHexColor("#4186e0"), parseHexColor("#e8384f")), result.map { it.color })
            result.forEach { assertNull(it.identity) }
        }
    }

    @Test
    fun `a project or list slot without a colour has none`() {
        val result = items(
            listOf(BottomBarSlot(BottomBarSlotType.PROJECT, "7"), BottomBarSlot(BottomBarSlotType.CUSTOM_LIST, "l1")),
        ).drop(1)

        result.forEach { assertNull(it.color, it.routeName) }
    }
}
