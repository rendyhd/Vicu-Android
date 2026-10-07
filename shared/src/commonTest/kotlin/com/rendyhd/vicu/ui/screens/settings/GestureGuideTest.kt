package com.rendyhd.vicu.ui.screens.settings

import com.rendyhd.vicu.data.local.ScheduleAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GestureGuideTest {

    @Test
    fun `the swipe-left text follows the configured action`() {
        assertEquals("Set the due date to today", swipeLeftDescription(ScheduleAction.DUE_TODAY))
        assertEquals("Mark the task urgent (priority 4)", swipeLeftDescription(ScheduleAction.PRIORITY_URGENT))
    }

    @Test
    fun `every configurable action has its own text`() {
        val texts = ScheduleAction.entries.map { swipeLeftDescription(it) }
        assertEquals(ScheduleAction.entries.size, texts.toSet().size)
    }

    @Test
    fun `the undo tip describes the held row, not a snackbar`() {
        assertTrue("snackbar" !in UNDO_TIP.lowercase())
        assertTrue("checkbox" in UNDO_TIP.lowercase())
    }
}
