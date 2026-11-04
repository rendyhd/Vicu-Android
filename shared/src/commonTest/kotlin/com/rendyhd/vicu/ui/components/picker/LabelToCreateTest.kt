package com.rendyhd.vicu.ui.components.picker

import com.rendyhd.vicu.domain.model.Label
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LabelToCreateTest {
    private val labels = listOf(Label(id = 1, title = "Work"), Label(id = 2, title = "Home"))

    @Test
    fun `a search with no exact match can create that label`() {
        assertEquals("Wor", labelToCreateFrom(" Wor ", labels))
        assertEquals("Errands", labelToCreateFrom("Errands", labels))
    }

    @Test
    fun `an exact match in any case creates nothing`() {
        assertNull(labelToCreateFrom("work", labels))
        assertNull(labelToCreateFrom(" HOME ", labels))
    }

    @Test
    fun `an empty search creates nothing`() {
        assertNull(labelToCreateFrom("   ", labels))
        assertNull(labelToCreateFrom("", labels))
    }
}
