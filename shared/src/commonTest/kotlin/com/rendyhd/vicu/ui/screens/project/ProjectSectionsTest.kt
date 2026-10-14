package com.rendyhd.vicu.ui.screens.project

import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ProjectSectionsTest {

    private fun p(id: Long, parent: Long = 0L, position: Double = 0.0): Project =
        Project(id = id, title = "P$id", parentProjectId = parent, position = position)

    private fun t(id: Long, position: Double = 0.0): Task =
        Task(id = id, title = "t$id", position = position)

    // collectDescendants ---------------------------------------------------

    @Test
    fun `collectDescendants returns all levels under root in position order`() {
        val projects = listOf(
            p(1),
            p(10, parent = 1, position = 20.0),
            p(11, parent = 1, position = 10.0),
            p(100, parent = 10),
            p(2), // unrelated root
        )
        val ids = collectDescendants(1L, projects).map { it.id }
        // 11 (pos 10) before 10 (pos 20); 100 follows its parent 10
        assertEquals(listOf(11L, 10L, 100L), ids)
    }

    @Test
    fun `collectDescendants terminates on cyclic parents`() {
        val projects = listOf(p(1), p(2, parent = 3), p(3, parent = 2))
        // 2<->3 cycle is unreachable from root 1
        assertEquals(emptyList<Long>(), collectDescendants(1L, projects).map { it.id })
    }

    @Test
    fun `directChildProjects returns only immediate children in position order`() {
        val projects = listOf(
            p(1),
            p(10, parent = 1, position = 20.0),
            p(11, parent = 1, position = 10.0),
            p(100, parent = 10, position = 1.0),
            p(2),
        )

        assertEquals(listOf(11L, 10L), directChildProjects(1L, projects).map { it.id })
    }

    // buildSectionTree -----------------------------------------------------

    @Test
    fun `buildSectionTree nests children and attaches tasks`() {
        val projects = listOf(p(1), p(10, parent = 1), p(100, parent = 10))
        val tasks = mapOf(10L to listOf(t(5)), 100L to listOf(t(7), t(8)))
        val tree = buildSectionTree(1L, projects, tasks)

        assertEquals(listOf(10L), tree.map { it.project.id })
        val s10 = tree[0]
        assertEquals(listOf(5L), s10.tasks.map { it.id })
        assertEquals(listOf(100L), s10.children.map { it.project.id })
        assertEquals(listOf(7L, 8L), s10.children[0].tasks.map { it.id })
    }

    @Test
    fun `buildSectionTree orders siblings by position`() {
        val projects = listOf(p(1), p(10, parent = 1, position = 30.0), p(11, parent = 1, position = 5.0))
        val tree = buildSectionTree(1L, projects, emptyMap())
        assertEquals(listOf(11L, 10L), tree.map { it.project.id })
    }

    // toggleSectionExpanded ------------------------------------------------

    @Test
    fun `toggleSectionExpanded flips only the matching nested node`() {
        val tree = listOf(
            ProjectSection(p(10), emptyList(), listOf(ProjectSection(p(100), emptyList()))),
        )
        val toggled = toggleSectionExpanded(tree, 100L)
        assertTrue(toggled[0].isExpanded)               // unchanged
        assertFalse(toggled[0].children[0].isExpanded)  // flipped
    }

    // preserveExpansion ----------------------------------------------------

    @Test
    fun `preserveExpansion carries old collapsed state onto new tree by id`() {
        val old = listOf(
            ProjectSection(p(10), emptyList(), listOf(ProjectSection(p(100), emptyList(), isExpanded = false))),
        )
        val new = listOf(
            ProjectSection(p(10), listOf(t(1)), listOf(ProjectSection(p(100), listOf(t(2))))),
        )
        val merged = preserveExpansion(new, old)
        assertFalse(merged[0].children[0].isExpanded) // collapsed state preserved
        assertEquals(listOf(2L), merged[0].children[0].tasks.map { it.id }) // new tasks kept
    }

    // moveTaskInSections ---------------------------------------------------

    @Test
    fun `moveTaskInSections reorders within the section holding the task`() {
        val tree = listOf(
            ProjectSection(p(10), emptyList(), listOf(
                ProjectSection(p(100), listOf(t(1, 10.0), t(2, 20.0), t(3, 30.0))),
            )),
        )
        val moved = moveTaskInSections(tree, fromId = 3, toId = 1)
        assertEquals(listOf(3L, 1L, 2L), moved!![0].children[0].tasks.map { it.id })
    }

    @Test
    fun `moveTaskInSections returns null for a cross-section move`() {
        val tree = listOf(
            ProjectSection(p(10), listOf(t(1, 10.0))),
            ProjectSection(p(20), listOf(t(2, 10.0))),
        )
        assertNull(moveTaskInSections(tree, fromId = 1, toId = 2))
    }

    @Test
    fun `moveTaskInSections returns null when the task is absent`() {
        val tree = listOf(ProjectSection(p(10), listOf(t(1))))
        assertNull(moveTaskInSections(tree, fromId = 99, toId = 1))
    }

    // findTaskGroup --------------------------------------------------------

    @Test
    fun `findTaskGroup locates the nested section owning the task`() {
        val tree = listOf(
            ProjectSection(p(10), emptyList(), listOf(ProjectSection(p(100), listOf(t(7))))),
        )
        assertEquals(100L, findTaskGroup(tree, 7L)?.project?.id)
        assertNull(findTaskGroup(tree, 8L))
    }

    // hasAnyTask -----------------------------------------------------------

    @Test
    fun `hasAnyTask is true when only a deep child has tasks`() {
        val tree = listOf(
            ProjectSection(p(10), emptyList(), listOf(ProjectSection(p(100), listOf(t(1))))),
        )
        assertTrue(hasAnyTask(tree))
    }

    @Test
    fun `hasAnyTask is false when the whole tree is empty`() {
        val tree = listOf(ProjectSection(p(10), emptyList(), listOf(ProjectSection(p(100), emptyList()))))
        assertFalse(hasAnyTask(tree))
    }

    // totalTaskCount -------------------------------------------------------

    @Test
    fun `totalTaskCount sums tasks across the whole subtree`() {
        val section = ProjectSection(
            p(10), listOf(t(1)), // 1 direct task
            listOf(
                ProjectSection(
                    p(100), listOf(t(2), t(3)), // child: 2 tasks
                    listOf(ProjectSection(p(1000), listOf(t(4)))), // grandchild: 1 task
                ),
            ),
        )
        assertEquals(4, totalTaskCount(section))
        // a leaf section with 2 tasks returns 2
        assertEquals(2, totalTaskCount(ProjectSection(p(20), listOf(t(5), t(6)))))
    }

    // supplementary coverage ----------------------------------------------

    @Test
    fun `preserveExpansion keeps the restored collapsed state of a node old has not seen`() {
        val restored = restoreExpansion(
            listOf(ProjectSection(p(10), emptyList(), listOf(ProjectSection(p(100), emptyList())))),
            collapsedSectionIds = setOf(10L, 100L),
        )

        // A fresh ViewModel has no old tree at all.
        val merged = preserveExpansion(restored, emptyList())

        assertFalse(merged[0].isExpanded)
        assertFalse(merged[0].children[0].isExpanded)
    }

    @Test
    fun `preserveExpansion defaults a new node absent from old to expanded`() {
        val old = listOf(ProjectSection(p(10), emptyList()))
        val new = listOf(
            ProjectSection(p(10), emptyList(), listOf(ProjectSection(p(100), emptyList()))),
        )
        val merged = preserveExpansion(new, old)
        // 100 has no counterpart in old -> defaults to expanded
        assertTrue(merged[0].children[0].isExpanded)
    }

    @Test
    fun `restoreExpansion applies persisted collapsed state at every depth`() {
        val tree = listOf(
            ProjectSection(
                p(10),
                emptyList(),
                listOf(ProjectSection(p(100), emptyList())),
            ),
            ProjectSection(p(20), emptyList()),
        )

        val restored = restoreExpansion(tree, setOf(10L, 100L))

        assertFalse(restored[0].isExpanded)
        assertFalse(restored[0].children[0].isExpanded)
        assertTrue(restored[1].isExpanded)
    }

    @Test
    fun `findProjectSection locates a nested section`() {
        val nested = ProjectSection(p(100), emptyList())
        val tree = listOf(ProjectSection(p(10), emptyList(), listOf(nested)))

        assertEquals(nested, findProjectSection(tree, 100L))
        assertNull(findProjectSection(tree, 999L))
    }

    @Test
    fun `moveTaskInSections returns null when a same-section move involves a dated task`() {
        val dated = Task(id = 2, title = "t2", dueDate = "2026-05-10T00:00:00Z", position = 20.0)
        val tree = listOf(ProjectSection(p(10), listOf(t(1, 10.0), dated)))
        assertNull(moveTaskInSections(tree, fromId = 1, toId = 2))
    }

    @Test
    fun `buildSectionTree and collectDescendants return empty for an empty project list`() {
        assertEquals(emptyList<Long>(), collectDescendants(1L, emptyList()).map { it.id })
        assertEquals(emptyList<ProjectSection>(), buildSectionTree(1L, emptyList(), emptyMap()))
    }
}
