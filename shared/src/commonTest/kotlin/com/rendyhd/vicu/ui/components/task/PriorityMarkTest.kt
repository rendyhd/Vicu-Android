package com.rendyhd.vicu.ui.components.task

import com.rendyhd.vicu.ui.theme.DesignTokensFixture
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The priority mark equals design-tokens-v1.json `priority` and the desktop's bar geometry. */
class PriorityMarkTest {

    @Test
    fun `every level of the fixture has the mark the fixture names`() {
        val levels = DesignTokensFixture.root.getValue("priority").jsonObject.getValue("levels").jsonObject
        assertEquals(setOf("1", "2", "3", "4", "5"), levels.keys)
        for ((level, entry) in levels) {
            val mark = entry.jsonObject.getValue("mark").jsonPrimitive.content
            assertEquals(mark, priorityMarkKind(level.toInt())?.token, "level $level")
        }
    }

    @Test
    fun `no priority and out of range levels draw nothing`() {
        assertNull(priorityMarkKind(0))
        assertNull(priorityMarkKind(6))
        assertNull(priorityMarkKind(-1))
    }

    @Test
    fun `bars fill from the left and keep three slots`() {
        for ((kind, filled) in listOf(PriorityMarkKind.BARS_1 to 1, PriorityMarkKind.BARS_2 to 2, PriorityMarkKind.BARS_3 to 3)) {
            val bars = priorityBars(kind)
            assertEquals(3, bars.size, "$kind slots")
            assertEquals(List(3) { it < filled }, bars.map { it.on }, "$kind filled")
        }
        assertTrue(priorityBars(PriorityMarkKind.SQUARE_BANG).isEmpty())
    }

    @Test
    fun `bar geometry is the desktop's on the 14 grid`() {
        val bars = priorityBars(PriorityMarkKind.BARS_3)
        assertEquals(listOf(1.5f, 5.5f, 9.5f), bars.map { it.x })
        assertEquals(listOf(8f, 5f, 2f), bars.map { it.y })
        assertEquals(listOf(5f, 8f, 11f), bars.map { it.height })
        assertEquals(listOf(3f, 3f, 3f), bars.map { it.width })
        // Every bar rests on the same baseline and stays inside the grid.
        for (bar in bars) {
            assertEquals(13f, bar.y + bar.height)
            assertTrue(bar.x + bar.width <= PRIORITY_MARK_GRID)
        }
    }
}
