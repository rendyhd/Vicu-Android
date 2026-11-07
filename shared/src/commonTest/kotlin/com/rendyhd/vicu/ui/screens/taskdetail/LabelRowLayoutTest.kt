package com.rendyhd.vicu.ui.screens.taskdetail

import kotlin.test.Test
import kotlin.test.assertEquals

class LabelRowLayoutTest {
    @Test
    fun oneLineWidthCountsTheGapsBetweenChips() {
        assertEquals(0, widthOfOneLine(emptyList(), gap = 6))
        assertEquals(40, widthOfOneLine(listOf(40), gap = 6))
        assertEquals(62, widthOfOneLine(listOf(40, 10, 0), gap = 6))
    }

    @Test
    fun chipsWrapWhenTheNextOneNoLongerFits() {
        assertEquals(
            listOf(listOf(0, 1), listOf(2), listOf(3, 4)),
            wrapIntoLines(listOf(40, 40, 90, 30, 30), gap = 6, maxWidth = 100),
        )
    }

    @Test
    fun aChipExactlyAsWideAsTheLineFits() {
        assertEquals(listOf(listOf(0, 1)), wrapIntoLines(listOf(47, 47), gap = 6, maxWidth = 100))
    }

    @Test
    fun aChipWiderThanTheLineHasItsOwnLine() {
        assertEquals(
            listOf(listOf(0), listOf(1), listOf(2)),
            wrapIntoLines(listOf(20, 150, 20), gap = 6, maxWidth = 100),
        )
    }
}
