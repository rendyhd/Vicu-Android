package com.rendyhd.vicu.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The small pure pieces behind the drawer's drag and drop. */
class DrawerReorderTest {

    // --- list keys ---

    @Test
    fun `a row key names its group and its id`() {
        assertEquals("project/12", drawerKey(DrawerGroup.PROJECT, 12L))
        assertEquals("list/abc", drawerKey(DrawerGroup.LIST, "abc"))
        assertEquals("label/3", drawerKey(DrawerGroup.LABEL, 3L))
    }

    @Test
    fun `a key reads back into its group and id`() {
        assertEquals(DrawerKey(DrawerGroup.PROJECT, "12"), parseDrawerKey("project/12"))
        assertEquals(DrawerKey(DrawerGroup.LIST, "a/b"), parseDrawerKey("list/a/b"), "an id may hold a slash")
        assertEquals(DrawerKey(DrawerGroup.LABEL, "3"), parseDrawerKey(drawerKey(DrawerGroup.LABEL, 3L)))
    }

    @Test
    fun `keys of rows that cannot be dragged are not rows of a group`() {
        assertNull(parseDrawerKey("header_projects"))
        assertNull(parseDrawerKey("smart_today"))
        assertNull(parseDrawerKey("project/"))
        assertNull(parseDrawerKey(42L))
        assertNull(parseDrawerKey(null))
    }

    // --- moving a row ---

    @Test
    fun `an item moves into the slot of the one it is dragged over`() {
        val items = listOf("a", "b", "c", "d")

        assertEquals(listOf("b", "c", "a", "d"), moveItem(items, from = "a", to = "c") { it })
        assertEquals(listOf("d", "a", "b", "c"), moveItem(items, from = "d", to = "a") { it })
    }

    @Test
    fun `no move for the same item or an item that is not there`() {
        val items = listOf("a", "b")

        assertNull(moveItem(items, from = "a", to = "a") { it })
        assertNull(moveItem(items, from = "a", to = "z") { it })
        assertNull(moveItem(items, from = "z", to = "a") { it })
    }

    // --- the order just dropped ---

    @Test
    fun `items follow the order given and the others come last in their own order`() {
        val items = listOf("a", "b", "c", "d")

        assertEquals(listOf("c", "a", "b", "d"), orderedBy(items, listOf("c", "a")) { it })
        assertEquals(listOf("b", "a", "c", "d"), orderedBy(items, listOf("b", "a", "zzz")) { it })
        assertEquals(items, orderedBy(items, emptyList()) { it })
    }

    // --- indentation ---

    @Test
    fun `projects are indented by level up to a limit`() {
        assertEquals(0, projectIndentLevel(0))
        assertEquals(3, projectIndentLevel(3))
        assertEquals(MAX_INDENT_LEVELS, projectIndentLevel(40), "a deep tree must not push the title off the sheet")
        assertEquals(0, projectIndentLevel(-1))
    }

    @Test
    fun `the button of a project says what it does to that project`() {
        assertEquals("Collapse Work", projectToggleDescription("Work", expanded = true))
        assertEquals("Expand Work", projectToggleDescription("Work", expanded = false))
    }
}
