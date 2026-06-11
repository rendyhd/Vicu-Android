package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Task

/** Vikunja position spacing used when appending to the end of a list view. */
const val POSITION_STEP = 65_536.0

private fun isDated(t: Task): Boolean =
    t.dueDate.isNotBlank() && !DateUtils.isNullDate(t.dueDate)

/** Undated tasks form the manually orderable block; dated rows are pinned by due-date sort. */
fun isManuallyOrdered(t: Task): Boolean = !isDated(t)

/**
 * Move [fromId] to the slot currently occupied by [toId]. Returns null (move vetoed)
 * when either id is missing or either task is dated: sortProjectTasks pins dated tasks
 * first by due date, so a cross-block move would just snap back on the next emission.
 */
fun moveTaskInList(tasks: List<Task>, fromId: Long, toId: Long): List<Task>? {
    val fromIdx = tasks.indexOfFirst { it.id == fromId }
    val toIdx = tasks.indexOfFirst { it.id == toId }
    if (fromIdx < 0 || toIdx < 0 || fromIdx == toIdx) return null
    if (isDated(tasks[fromIdx]) || isDated(tasks[toIdx])) return null
    return tasks.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
}

/** Position halfway between neighbors; half of next at the top; one step past prev at the end. */
fun computeDropPosition(prev: Double?, next: Double?): Double = when {
    prev != null && next != null -> (prev + next) / 2.0
    next != null -> next / 2.0
    prev != null -> prev + POSITION_STEP
    else -> POSITION_STEP
}

/**
 * New position for [taskId] given its neighbors in [tasks] (the already-reordered,
 * as-displayed list). A dated previous row is ignored — its position is meaningless
 * for the undated ordering.
 */
fun dropPositionFor(tasks: List<Task>, taskId: Long): Double? {
    val idx = tasks.indexOfFirst { it.id == taskId }
    if (idx < 0) return null
    val prev = tasks.getOrNull(idx - 1)?.takeUnless { isDated(it) }?.position
    val next = tasks.getOrNull(idx + 1)?.position
    return computeDropPosition(prev, next)
}
