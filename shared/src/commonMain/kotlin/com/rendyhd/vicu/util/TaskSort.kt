package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskOrder

/**
 * Sort a project's task list in [order]. The default mirrors the desktop client (commit b1fb6f7):
 *
 *   1. Tasks with a real due date come first, in ascending due-date order.
 *   2. Undated tasks follow, in their custom (position) order.
 *
 * Every other order falls back to the manual (position) order for ties, so equal rows keep a
 * stable place. "Null date" (Vikunja's `0001-01-01T00:00:00Z` sentinel) and blank strings count as
 * undated.
 */
fun sortProjectTasks(tasks: List<Task>, order: TaskOrder = TaskOrder.DEFAULT): List<Task> =
    tasks.sortedWith(taskComparator(order))

/** The comparator behind [sortProjectTasks]. */
fun taskComparator(order: TaskOrder): Comparator<Task> = when (order) {
    TaskOrder.DUE_DATE_FIRST -> dueDateFirst
    TaskOrder.DUE_DATE_THEN_PRIORITY -> dueDateThenPriority
    TaskOrder.MANUAL -> byPosition
    // Vikunja priorities run 0 (unset) to 5 (do now): the highest first, unset last.
    TaskOrder.PRIORITY -> compareByDescending<Task> { it.priority }.then(dueDateFirst)
    TaskOrder.TITLE -> compareBy<Task, String>(String.CASE_INSENSITIVE_ORDER) { it.title }.then(byPosition)
    TaskOrder.NEWEST -> compareByDescending<Task> { it.created }.then(byPosition)
    TaskOrder.OLDEST -> compareBy<Task> { it.created }.then(byPosition)
}

private fun hasDueDate(t: Task): Boolean =
    t.dueDate.isNotBlank() && !DateUtils.isNullDate(t.dueDate)

private val byPosition: Comparator<Task> = compareBy { it.position }

/** Dated first by due date, then undated; within each, the highest priority first, then by position. */
private val dueDateThenPriority: Comparator<Task> = compareBy<Task> { !hasDueDate(it) }
    .thenBy { if (hasDueDate(it)) it.dueDate else "" }
    .thenByDescending { it.priority }
    .then(byPosition)

private val dueDateFirst: Comparator<Task> = Comparator { a, b ->
    val aDated = hasDueDate(a)
    val bDated = hasDueDate(b)
    when {
        aDated && !bDated -> -1
        !aDated && bDated -> 1
        aDated && bDated -> {
            val cmp = a.dueDate.compareTo(b.dueDate)
            if (cmp != 0) cmp else a.position.compareTo(b.position)
        }
        else -> a.position.compareTo(b.position)
    }
}
