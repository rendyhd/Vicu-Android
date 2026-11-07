package com.rendyhd.vicu.ui.components.task

import kotlin.test.Test
import kotlin.test.assertEquals

class ShownNotesHeightTest {
    @Test
    fun shortNotesShowWhole() {
        assertEquals(300, shownNotesHeight(full = 300, fold = 600, slack = 100, open = false))
    }

    @Test
    fun notesJustPastTheFoldShowWhole() {
        assertEquals(700, shownNotesHeight(full = 700, fold = 600, slack = 100, open = false))
    }

    @Test
    fun longNotesStopAtTheFold() {
        assertEquals(600, shownNotesHeight(full = 701, fold = 600, slack = 100, open = false))
    }

    @Test
    fun openNotesShowWhole() {
        assertEquals(5000, shownNotesHeight(full = 5000, fold = 600, slack = 100, open = true))
    }

    @Test
    fun withoutAFoldNotesShowWhole() {
        assertEquals(5000, shownNotesHeight(full = 5000, fold = null, slack = 100, open = false))
    }
}
