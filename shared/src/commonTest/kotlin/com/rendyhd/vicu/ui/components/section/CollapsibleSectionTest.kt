package com.rendyhd.vicu.ui.components.section

import kotlin.test.Test
import kotlin.test.assertEquals

/** What a screen reader hears for a section header. */
class CollapsibleSectionTest {

    @Test
    fun `an open section is announced by its name alone`() {
        assertEquals("Work", sectionDescription("Work", taskCount = 4, isExpanded = true))
    }

    @Test
    fun `a closed section also says how many tasks it hides`() {
        assertEquals("Work, 4 tasks", sectionDescription("Work", taskCount = 4, isExpanded = false))
        assertEquals("Work, 1 task", sectionDescription("Work", taskCount = 1, isExpanded = false))
    }

    @Test
    fun `an empty closed section has nothing to count`() {
        assertEquals("Work", sectionDescription("Work", taskCount = 0, isExpanded = false))
    }

    @Test
    fun `the state is expanded or collapsed`() {
        assertEquals("Expanded", sectionStateDescription(true))
        assertEquals("Collapsed", sectionStateDescription(false))
    }
}
