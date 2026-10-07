package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Task
import kotlin.test.Test
import kotlin.test.assertEquals

class TaskHierarchyTest {
    @Test
    fun `child is nested when its parent is in the same result`() {
        val parentReference = Task(id = 1, title = "Parent")
        val child = Task(
            id = 2,
            title = "Child",
            relatedTasks = mapOf(RelationKind.PARENTTASK to listOf(parentReference)),
        )
        val parent = parentReference.copy(
            relatedTasks = mapOf(RelationKind.SUBTASK to listOf(child)),
        )

        assertEquals(listOf(parent), listOf(parent, child).withoutNestedSubtasks())
    }

    @Test
    fun `child remains visible when a filter omits its parent`() {
        val child = Task(
            id = 2,
            title = "Matching child",
            relatedTasks = mapOf(
                RelationKind.PARENTTASK to listOf(Task(id = 1, title = "Parent")),
            ),
        )

        assertEquals(listOf(child), listOf(child).withoutNestedSubtasks())
    }

    @Test
    fun `child stays nested when its completed parent is omitted`() {
        val child = Task(
            id = 2,
            title = "Child",
            relatedTasks = mapOf(
                RelationKind.PARENTTASK to listOf(Task(id = 1, title = "Parent", done = true)),
            ),
        )

        assertEquals(emptyList(), listOf(child).withoutNestedSubtasks())
    }

    @Test
    fun `a view that filters first can show a child of a completed parent`() {
        val child = Task(
            id = 2,
            title = "Matching child",
            relatedTasks = mapOf(
                RelationKind.PARENTTASK to listOf(Task(id = 1, title = "Parent", done = true)),
            ),
        )

        assertEquals(
            listOf(child),
            listOf(child).withoutNestedSubtasks(hideChildrenOfCompletedParents = false),
        )
    }

    @Test
    fun `a child is still nested under a parent that is in the filtered list`() {
        val parent = Task(id = 1, title = "Parent", done = true)
        val child = Task(
            id = 2,
            title = "Child",
            relatedTasks = mapOf(RelationKind.PARENTTASK to listOf(parent)),
        )

        assertEquals(
            listOf(parent),
            listOf(parent, child).withoutNestedSubtasks(hideChildrenOfCompletedParents = false),
        )
    }

    @Test
    fun `descendant helpers traverse multiple levels once`() {
        val grandchild = Task(id = 3, title = "Grandchild")
        val child = Task(
            id = 2,
            title = "Child",
            done = true,
            relatedTasks = mapOf(RelationKind.SUBTASK to listOf(grandchild)),
        )
        val parent = Task(
            id = 1,
            title = "Parent",
            relatedTasks = mapOf(RelationKind.SUBTASK to listOf(child)),
        )

        assertEquals(listOf(child, grandchild), parent.descendantsDepthFirst())
        assertEquals(listOf(grandchild), parent.unfinishedDescendants())
        assertEquals(1 to 2, parent.subtaskProgress())
    }
}
