package com.rendyhd.vicu.ui.screens.taskdetail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The detail screen's small text helpers. */
class TaskDetailValuesTest {

    @Test
    fun `priority names cover the whole range and nothing else`() {
        assertNull(priorityName(0))
        assertEquals("Low", priorityName(1))
        assertEquals("Medium", priorityName(2))
        assertEquals("High", priorityName(3))
        assertEquals("Urgent", priorityName(4))
        assertEquals("Do now", priorityName(5))
        assertNull(priorityName(6))
    }

    @Test
    fun `line breaks in a title become spaces`() {
        assertEquals("Buy milk", "Buy\nmilk".withoutLineBreaks())
        assertEquals("Buy milk", "Buy\r\nmilk".withoutLineBreaks())
        assertEquals("Buy milk", "Buy\rmilk".withoutLineBreaks())
        assertEquals("one two three ", "one\ntwo\r\nthree\n".withoutLineBreaks())
        assertEquals("unchanged", "unchanged".withoutLineBreaks())
    }
}
