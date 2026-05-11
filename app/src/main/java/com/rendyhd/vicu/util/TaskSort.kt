package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Task

/**
 * Sort a project's task list to mirror the desktop client (commit b1fb6f7):
 *
 *   1. Tasks with a real due date come first, in ascending due-date order.
 *   2. Undated tasks follow, in their custom (position) order.
 *
 * "Null date" (Vikunja's `0001-01-01T00:00:00Z` sentinel) and blank strings count as undated.
 */
fun sortProjectTasks(tasks: List<Task>): List<Task> {
    fun hasDueDate(t: Task): Boolean =
        t.dueDate.isNotBlank() && !DateUtils.isNullDate(t.dueDate)

    return tasks.sortedWith(
        Comparator { a, b ->
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
        },
    )
}
