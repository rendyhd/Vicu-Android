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

/**
 * Position halfway between neighbors; half of next at the top; one step past prev at the end.
 *
 * Known limitation: when neighbors carry Vikunja's default position 0.0 (legacy lists that were
 * never anchored), the result can equal a neighbor's position and the relative order is then
 * server-undefined; accepted per the plan — dragging to the end heals such lists.
 */
fun computeDropPosition(prev: Double?, next: Double?): Double = when {
    prev != null && next != null -> (prev + next) / 2.0
    next != null -> next / 2.0
    prev != null -> prev + POSITION_STEP
    else -> POSITION_STEP
}

/**
 * New position for [taskId] given its neighbors in [tasks] (the already-reordered,
 * as-displayed list). Dated neighbors are ignored — their positions are meaningless
 * for the undated ordering.
 */
fun dropPositionFor(tasks: List<Task>, taskId: Long): Double? {
    val idx = tasks.indexOfFirst { it.id == taskId }
    if (idx < 0) return null
    val prev = tasks.getOrNull(idx - 1)?.takeUnless { isDated(it) }?.position
    val next = tasks.getOrNull(idx + 1)?.takeUnless { isDated(it) }?.position
    return computeDropPosition(prev, next)
}

/** Neighbours closer than this have no room for another task between them. */
const val MIN_POSITION_GAP = 1.0

/** A task and the position it is to be given in its list view. */
data class PositionUpdate(val taskId: Long, val position: Double)

/**
 * What to send when a task is dropped: its own new position and, when the neighbours left no room
 * for it, fresh positions for the other manually ordered tasks of the list.
 */
data class DropPlan(val moved: PositionUpdate, val renumbered: List<PositionUpdate>) {
    /** The updates in the order to send them: the other tasks first, the dragged one last. */
    val updates: List<PositionUpdate> get() = renumbered + moved
}

/**
 * Where [taskId] belongs after a drag, given the list as it is now displayed ([tasks], the dragged
 * task already in its new place). The same plan the desktop app makes (`planMove`):
 *
 * - normally one position, halfway between the nearest manually ordered tasks above and below
 *   (half of the first one at the top, one step past the last one at the end), and nothing else
 *   changes;
 * - when those two are equal or closer than [MIN_POSITION_GAP] there is no room: the middle of two
 *   equal numbers is the same number, so the drag would change nothing. Every manually ordered task
 *   then gets a fresh position one [POSITION_STEP] apart in the displayed order, and only the ones
 *   whose position changes are listed.
 *
 * Dated tasks are skipped: they are listed by due date and their positions mean nothing. Null when
 * the task is not in the list or is dated (it cannot be dragged).
 */
fun planDrop(tasks: List<Task>, taskId: Long): DropPlan? {
    val index = tasks.indexOfFirst { it.id == taskId }
    if (index < 0 || !isManuallyOrdered(tasks[index])) return null

    var above = 0.0
    for (i in index - 1 downTo 0) {
        if (isManuallyOrdered(tasks[i])) {
            above = tasks[i].position
            break
        }
    }
    var below: Double? = null
    for (i in index + 1 until tasks.size) {
        if (isManuallyOrdered(tasks[i])) {
            below = tasks[i].position
            break
        }
    }

    // At the end there is always room: one step past the last task, as new tasks are placed.
    if (below == null) return DropPlan(PositionUpdate(taskId, above + POSITION_STEP), emptyList())
    if (below - above >= MIN_POSITION_GAP) {
        return DropPlan(PositionUpdate(taskId, (above + below) / 2.0), emptyList())
    }

    var next = POSITION_STEP
    var movedPosition = next
    val renumbered = mutableListOf<PositionUpdate>()
    for (task in tasks) {
        if (!isManuallyOrdered(task)) continue
        val position = next
        next += POSITION_STEP
        if (task.id == taskId) {
            movedPosition = position
        } else if (task.position != position) {
            renumbered += PositionUpdate(task.id, position)
        }
    }
    return DropPlan(PositionUpdate(taskId, movedPosition), renumbered)
}
