package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Task

/**
 * Hide a subtask from the top level when one of its parents is present in the
 * same result or is completed. Active parents omitted by a search/date filter
 * still allow a contextual child-only match; completing a parent never promotes
 * unfinished children into unrelated root tasks.
 *
 * A view that applies its own conditions (Tag, custom lists) filters first and calls this on
 * what is left with [hideChildrenOfCompletedParents] false: a subtask that matches then shows
 * whenever its parent is not among the matches, whether the parent is open or completed.
 */
fun List<Task>.withoutNestedSubtasks(hideChildrenOfCompletedParents: Boolean = true): List<Task> {
    val visibleIds = mapTo(HashSet(size)) { it.id }
    return filter { task ->
        task.relatedTasks[RelationKind.PARENTTASK]
            .orEmpty()
            .none { parent -> parent.id in visibleIds || (hideChildrenOfCompletedParents && parent.done) }
    }
}

/** All descendants in depth-first order, with each child before its own descendants. */
fun Task.descendantsDepthFirst(): List<Task> {
    val result = mutableListOf<Task>()
    val visited = mutableSetOf(id)

    fun visit(parent: Task) {
        parent.relatedTasks[RelationKind.SUBTASK].orEmpty().forEach { child ->
            if (!visited.add(child.id)) return@forEach
            result += child
            visit(child)
        }
    }

    visit(this)
    return result
}

fun Task.unfinishedDescendants(): List<Task> = descendantsDepthFirst().filterNot { it.done }

fun Task.subtaskProgress(): Pair<Int, Int> {
    val descendants = descendantsDepthFirst()
    return descendants.count { it.done } to descendants.size
}
