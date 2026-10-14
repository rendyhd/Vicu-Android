package com.rendyhd.vicu.ui.navigation

import com.rendyhd.vicu.domain.model.Project
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The drawer's project tree: any depth, collapsible, and reorderable among siblings (UI-28). */
class ProjectTreeTest {

    private fun project(id: Long, parent: Long = 0, position: Double = id.toDouble()) =
        Project(id = id, title = "P$id", parentProjectId = parent, position = position)

    private fun List<ProjectNode>.shape(): List<Any> = map { listOf(it.project.id, it.children.shape()) }

    private fun List<ProjectRow>.ids() = map { it.project.id }

    // --- building the tree ---

    @Test
    fun `projects nest to any depth, siblings by position`() {
        val tree = buildProjectTree(
            listOf(
                project(1, position = 20.0),
                project(2, position = 10.0),
                project(3, parent = 1, position = 2.0),
                project(4, parent = 1, position = 1.0),
                project(5, parent = 4),
                project(6, parent = 5),
            ),
        )

        assertEquals(
            listOf(
                listOf(2L, emptyList<Any>()),
                listOf(1L, listOf(listOf(4L, listOf(listOf(5L, listOf(listOf(6L, emptyList<Any>()))))), listOf(3L, emptyList<Any>()))),
            ),
            tree.shape(),
        )
    }

    @Test
    fun `projects that share a position keep a stable order by id`() {
        val tree = buildProjectTree(listOf(project(3, position = 0.0), project(1, position = 0.0), project(2, position = 0.0)))

        assertEquals(listOf(1L, 2L, 3L), tree.map { it.project.id })
    }

    @Test
    fun `a project whose parent is not in the list becomes a root`() {
        // The parent is archived, or is the inbox: neither is listed.
        val tree = buildProjectTree(listOf(project(2, parent = 1), project(3, parent = 2)))

        assertEquals(listOf(listOf(2L, listOf(listOf(3L, emptyList<Any>())))), tree.shape())
    }

    @Test
    fun `projects that are each other's parents are still listed`() {
        val tree = buildProjectTree(listOf(project(1, parent = 2), project(2, parent = 1), project(3)))

        assertEquals(setOf(1L, 2L, 3L), flatIds(tree).toSet(), "a broken parent chain must not hide projects")
        assertEquals(3, flatIds(tree).size, "and none is listed twice")
    }

    @Test
    fun `a project that is its own parent is a root`() {
        val tree = buildProjectTree(listOf(project(1, parent = 1)))

        assertEquals(listOf(1L), tree.map { it.project.id })
    }

    private fun flatIds(tree: List<ProjectNode>): List<Long> =
        tree.flatMap { listOf(it.project.id) + flatIds(it.children) }

    // --- the visible rows ---

    private val tree = buildProjectTree(
        listOf(
            project(1),
            project(2),
            project(3, parent = 1),
            project(4, parent = 3),
            project(5, parent = 1),
        ),
    )

    @Test
    fun `rows come in display order with their depth and parent`() {
        val rows = visibleProjectRows(tree, collapsed = emptySet())

        assertEquals(listOf(1L, 3L, 4L, 5L, 2L), rows.ids())
        assertEquals(listOf(0, 1, 2, 1, 0), rows.map { it.depth })
        assertEquals(listOf(0L, 1L, 3L, 1L, 0L), rows.map { it.parentId })
    }

    @Test
    fun `a row knows whether it has children and whether they show`() {
        val rows = visibleProjectRows(tree, collapsed = emptySet())

        assertEquals(listOf(true, true, false, false, false), rows.map { it.hasChildren })
        assertEquals(listOf(true, true, false, false, false), rows.map { it.expanded })
    }

    @Test
    fun `a collapsed project hides everything below it, and only that`() {
        val rows = visibleProjectRows(tree, collapsed = setOf(1L))

        assertEquals(listOf(1L, 2L), rows.ids())
        assertTrue(rows.first().hasChildren)
        assertFalse(rows.first().expanded)
    }

    @Test
    fun `collapsing an inner project hides its children but not its siblings`() {
        val rows = visibleProjectRows(tree, collapsed = setOf(3L))

        assertEquals(listOf(1L, 3L, 5L, 2L), rows.ids())
    }

    @Test
    fun `collapsing a project without children changes nothing`() {
        assertEquals(
            visibleProjectRows(tree, emptySet()).ids(),
            visibleProjectRows(tree, setOf(2L, 4L, 99L)).ids(),
        )
    }

    // --- dragging among siblings ---

    private fun rowsOf(vararg projects: Project, collapsed: Set<Long> = emptySet()) =
        visibleProjectRows(buildProjectTree(projects.toList()), collapsed)

    @Test
    fun `a root dragged over the next root swaps with it`() {
        val rows = rowsOf(project(1), project(2), project(3))

        assertEquals(listOf(2L, 1L, 3L), moveProjectRow(rows, movedId = 1, targetId = 2)!!.ids())
        assertEquals(listOf(1L, 3L, 2L), moveProjectRow(rows, movedId = 3, targetId = 2)!!.ids())
    }

    @Test
    fun `a project dragged over a sibling that is open lands past all of its children`() {
        val rows = rowsOf(project(1), project(2), project(3, parent = 2), project(4, parent = 2), project(5))

        val moved = moveProjectRow(rows, movedId = 1, targetId = 2)!!

        assertEquals(listOf(2L, 3L, 4L, 1L, 5L), moved.ids())
        assertEquals(listOf(0, 1, 1, 0, 0), moved.map { it.depth })
    }

    @Test
    fun `dragging up over the children of a sibling means the sibling`() {
        val rows = rowsOf(project(1), project(2, parent = 1), project(3, parent = 1), project(4))

        // The row above project 4 is project 3, a child of 1: the target is 1 and its block.
        val moved = moveProjectRow(rows, movedId = 4, targetId = 3)!!

        assertEquals(listOf(4L, 1L, 2L, 3L), moved.ids())
    }

    @Test
    fun `a project with open children moves as one block`() {
        val rows = rowsOf(project(1), project(2, parent = 1), project(3, parent = 2), project(4), project(5))

        val moved = moveProjectRow(rows, movedId = 1, targetId = 4)!!

        assertEquals(listOf(4L, 1L, 2L, 3L, 5L), moved.ids())
        assertEquals(listOf(0, 0, 1, 2, 0), moved.map { it.depth })
    }

    @Test
    fun `children are reordered among their own siblings`() {
        val rows = rowsOf(project(1), project(2, parent = 1), project(3, parent = 1), project(4, parent = 1), project(5))

        val moved = moveProjectRow(rows, movedId = 4, targetId = 2)!!

        assertEquals(listOf(1L, 4L, 2L, 3L, 5L), moved.ids())
        assertEquals(1L, moved[1].parentId)
    }

    @Test
    fun `a project cannot be dragged out of its level`() {
        val rows = rowsOf(project(1), project(2, parent = 1), project(3), project(4, parent = 3))

        assertNull(moveProjectRow(rows, movedId = 2, targetId = 3), "a child onto a root")
        assertNull(moveProjectRow(rows, movedId = 2, targetId = 4), "a child onto a child of another project")
    }

    @Test
    fun `a project cannot be dropped onto its own children`() {
        val rows = rowsOf(project(1), project(2, parent = 1))

        assertNull(moveProjectRow(rows, movedId = 1, targetId = 2))
    }

    @Test
    fun `an unknown project or the same one is no move`() {
        val rows = rowsOf(project(1), project(2))

        assertNull(moveProjectRow(rows, movedId = 1, targetId = 1))
        assertNull(moveProjectRow(rows, movedId = 1, targetId = 99))
        assertNull(moveProjectRow(rows, movedId = 99, targetId = 1))
    }

    @Test
    fun `the siblings of a row are its whole level under the same parent`() {
        val rows = rowsOf(project(1), project(2, parent = 1), project(3, parent = 1), project(4), project(5, parent = 3))

        assertEquals(listOf(2L, 3L), siblingIds(rows, 3))
        assertEquals(listOf(1L, 4L), siblingIds(rows, 1))
        assertEquals(emptyList(), siblingIds(rows, 99))
    }

    // --- the order a drop has just made ---

    @Test
    fun `an order that was just dropped is shown before the stored one catches up`() {
        val reordered = reorderSiblings(tree, parentId = 0, ids = listOf(2L, 1L))

        assertEquals(listOf(2L, 1L), reordered.map { it.project.id })
        assertEquals(listOf(3L, 5L), reordered.last().children.map { it.project.id }, "the children went with their parent")
    }

    @Test
    fun `the order of children is applied inside their parent`() {
        val reordered = reorderSiblings(tree, parentId = 1, ids = listOf(5L, 3L))

        assertEquals(listOf(1L, 2L), reordered.map { it.project.id })
        assertEquals(listOf(5L, 3L), reordered.first().children.map { it.project.id })
        assertEquals(listOf(4L), reordered.first().children.last().children.map { it.project.id })
    }

    @Test
    fun `projects the order does not name keep their place after the ones it does`() {
        val reordered = reorderSiblings(tree, parentId = 0, ids = listOf(2L, 99L))

        assertEquals(listOf(2L, 1L), reordered.map { it.project.id })
    }
}
