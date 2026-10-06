package com.rendyhd.vicu.ui.components.shared

import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.CustomListFilter
import com.rendyhd.vicu.domain.model.toWire
import com.rendyhd.vicu.util.CustomListFilterBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The "Include overdue tasks" switch of the custom-list editor: on by default, shown only for the
 * windows it affects, and written as an absent key unless the user turned it off.
 */
class CustomListEditorTest {

    @Test
    fun `a list without the key, or with true, opens with the switch on`() {
        assertTrue(includeOverdueSwitchOn(null))
        assertTrue(includeOverdueSwitchOn(true))
        assertFalse(includeOverdueSwitchOn(false))
    }

    @Test
    fun `the switch writes an absent key when on and false when off`() {
        assertNull(includeOverdueToStore(switchOn = true))
        assertEquals(false, includeOverdueToStore(switchOn = false))
    }

    @Test
    fun `saving a list keeps the key absent unless the user turned overdue off`() {
        fun saved(switchOn: Boolean) = CustomList(
            id = "a",
            name = "A",
            filter = CustomListFilter(dueDateFilter = "today", includeOverdue = includeOverdueToStore(switchOn)),
        ).toWire().filter.includeOverdue

        assertNull(saved(switchOn = true))
        assertEquals(false, saved(switchOn = false))
    }

    @Test
    fun `the switch is shown for today, this week and this month only`() {
        for (window in listOf("today", "this_week", "this_month")) {
            assertTrue(CustomListFilterBuilder.windowHonorsIncludeOverdue(window), window)
        }
        for (window in listOf("all", "overdue", "has_due_date", "no_due_date", "something_new")) {
            assertFalse(CustomListFilterBuilder.windowHonorsIncludeOverdue(window), window)
        }
    }
}
