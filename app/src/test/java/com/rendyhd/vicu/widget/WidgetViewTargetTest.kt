package com.rendyhd.vicu.widget

import com.rendyhd.vicu.ui.navigation.ViewTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetViewTargetTest {

    @Test
    fun `smart-list widgets open their own list`() {
        assertEquals(ViewTarget.Today, WidgetViewType.TODAY.toViewTarget(""))
        assertEquals(ViewTarget.Inbox, WidgetViewType.INBOX.toViewTarget(""))
        assertEquals(ViewTarget.Upcoming, WidgetViewType.UPCOMING.toViewTarget(""))
        assertEquals(ViewTarget.Anytime, WidgetViewType.ANYTIME.toViewTarget(""))
    }

    @Test
    fun `a project widget opens its project`() {
        assertEquals(ViewTarget.Project(42L), WidgetViewType.PROJECT.toViewTarget("42"))
    }

    @Test
    fun `a custom-list widget opens its list`() {
        assertEquals(ViewTarget.CustomList("c0ffee"), WidgetViewType.CUSTOM_LIST.toViewTarget("c0ffee"))
    }

    @Test
    fun `a widget without a usable id opens only the app`() {
        assertNull(WidgetViewType.PROJECT.toViewTarget(""))
        assertNull(WidgetViewType.PROJECT.toViewTarget("not-a-number"))
        assertNull(WidgetViewType.CUSTOM_LIST.toViewTarget(" "))
    }

    @Test
    fun `every widget type maps the same way the wire form reads it back`() {
        // The widget's action carries the target as strings; the activity parses them again.
        val withIds = mapOf(
            WidgetViewType.TODAY to "",
            WidgetViewType.INBOX to "",
            WidgetViewType.UPCOMING to "",
            WidgetViewType.ANYTIME to "",
            WidgetViewType.PROJECT to "7",
            WidgetViewType.CUSTOM_LIST to "abc",
        )
        WidgetViewType.entries.forEach { type ->
            val target = type.toViewTarget(withIds.getValue(type))!!
            assertEquals(type.name, target.typeName)
            assertEquals(target, ViewTarget.parse(target.typeName, target.idOrEmpty))
        }
    }
}
