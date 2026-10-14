package com.rendyhd.vicu.ui.components.task

import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.RelationKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwipeCompletionTest {

    private fun parent(vararg children: Task, done: Boolean = false) = Task(
        id = 1,
        title = "Parent",
        done = done,
        relatedTasks = mapOf(RelationKind.SUBTASK to children.toList()),
    )

    private fun child(id: Long, done: Boolean) = Task(id = id, title = "Child $id", done = done)

    @Test
    fun `asks before completing a parent with an unfinished subtask`() {
        assertTrue(completionNeedsSubtaskConfirmation(parent(child(2, done = false))))
    }

    @Test
    fun `does not ask when every subtask is already done`() {
        assertFalse(completionNeedsSubtaskConfirmation(parent(child(2, done = true))))
    }

    @Test
    fun `does not ask for a task without subtasks or one that is already done`() {
        assertFalse(completionNeedsSubtaskConfirmation(parent()))
        assertFalse(completionNeedsSubtaskConfirmation(parent(child(2, done = false), done = true)))
    }

    @Test
    fun `the decision follows the task it is given, so a refreshed task changes the answer`() {
        // The swipe handler keeps one lambda for the row's lifetime; it must evaluate whatever
        // task is current at swipe time, not the one the row was first composed with.
        val composedWith = parent()
        val latest = parent(child(2, done = false))

        assertFalse(completionNeedsSubtaskConfirmation(composedWith))
        assertTrue(completionNeedsSubtaskConfirmation(latest))
    }
}
