package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskOrder

/** Vikunja position spacing used when appending to the end of a list view. */
const val POSITION_STEP = 65_536.0

private fun isDated(t: Task): Boolean =
    t.dueDate.isNotBlank() && !DateUtils.isNullDate(t.dueDate)

/**
 * Whether [t] is placed by hand in a list sorted by [order]. In the default order the undated
 * tasks form the manually orderable block and dated rows are pinned by due-date sort; in the manual
 * order every task is; in the other orders none is (the sort decides every place).
 */
fun isManuallyOrdered(t: Task, order: TaskOrder = TaskOrder.DEFAULT): Boolean = when (order) {
    TaskOrder.DUE_DATE_FIRST -> !isDated(t)
    TaskOrder.MANUAL -> true
    else -> false
}

/**
 * Move [fromId] to the slot currently occupied by [toId]. Returns null (move vetoed)
 * when either id is missing or either task is not manually ordered in [order]: in the default
 * order sortProjectTasks pins dated tasks first by due date, so a cross-block move would just
 * snap back on the next emission.
 */
fun moveTaskInList(
    tasks: List<Task>,
    fromId: Long,
    toId: Long,
    order: TaskOrder = TaskOrder.DEFAULT,
): List<Task>? {
    val fromIdx = tasks.indexOfFirst { it.id == fromId }
    val toIdx = tasks.indexOfFirst { it.id == toId }
    if (fromIdx < 0 || toIdx < 0 || fromIdx == toIdx) return null
    if (!isManuallyOrdered(tasks[fromIdx], order) || !isManuallyOrdered(tasks[toIdx], order)) return null
    return tasks.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
}

/**
 * The task whose slot [taskId] takes when it is moved [offset] places (-1 up, 1 down) in [tasks],
 * the list as displayed; the one-step move a screen reader makes in place of a drag. Null when
 * there is no such slot or the move is vetoed (see [moveTaskInList]: dated tasks are not moved,
 * and nothing is moved past them).
 */
fun neighbourForMove(
    tasks: List<Task>,
    taskId: Long,
    offset: Int,
    order: TaskOrder = TaskOrder.DEFAULT,
): Long? {
    val index = tasks.indexOfFirst { it.id == taskId }
    if (index < 0) return null
    val target = tasks.getOrNull(index + offset) ?: return null
    return target.id.takeIf { moveTaskInList(tasks, taskId, target.id, order) != null }
}

/** Which one-step moves a task can make. */
data class MoveOptions(val up: Boolean, val down: Boolean)

/**
 * The one-step moves each task of [tasks] (as displayed) can make, by id, in one pass: the same
 * answer [neighbourForMove] gives task by task.
 */
fun moveOptions(tasks: List<Task>, order: TaskOrder = TaskOrder.DEFAULT): Map<Long, MoveOptions> {
    fun canSwap(a: Task, b: Task) = isManuallyOrdered(a, order) && isManuallyOrdered(b, order)
    val options = HashMap<Long, MoveOptions>(tasks.size * 2)
    tasks.forEachIndexed { index, task ->
        options[task.id] = MoveOptions(
            up = index > 0 && canSwap(task, tasks[index - 1]),
            down = index < tasks.lastIndex && canSwap(task, tasks[index + 1]),
        )
    }
    return options
}

/** [ids] with [id] moved [offset] places, or null when it is not listed or would leave the list. */
fun <T> moveIdBy(ids: List<T>, id: T, offset: Int): List<T>? {
    val index = ids.indexOf(id)
    val target = index + offset
    if (index < 0 || target < 0 || target > ids.lastIndex || offset == 0) return null
    return ids.toMutableList().apply { add(target, removeAt(index)) }
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
 * as-displayed list). Neighbors that are not manually ordered in [order] are ignored (dated ones
 * in the default order): their positions are meaningless for the manual ordering.
 */
fun dropPositionFor(tasks: List<Task>, taskId: Long, order: TaskOrder = TaskOrder.DEFAULT): Double? {
    val idx = tasks.indexOfFirst { it.id == taskId }
    if (idx < 0) return null
    val prev = tasks.getOrNull(idx - 1)?.takeIf { isManuallyOrdered(it, order) }?.position
    val next = tasks.getOrNull(idx + 1)?.takeIf { isManuallyOrdered(it, order) }?.position
    return computeDropPosition(prev, next)
}

/** Neighbours closer than this have no room for another task between them. */
const val MIN_POSITION_GAP = 1.0

/** An item of an ordered list (a task, a project) and the position it is to be given. */
data class PositionUpdate(val id: Long, val position: Double)

/** An item of an ordered list and the position it holds now. */
data class PositionedId(val id: Long, val position: Double)

/**
 * What to send when an item is dropped: its own new position and, when the neighbours left no room
 * for it, fresh positions for the other items of the list.
 */
data class DropPlan(val moved: PositionUpdate, val renumbered: List<PositionUpdate>) {
    /** The updates in the order to send them: the other items first, the dragged one last. */
    val updates: List<PositionUpdate> get() = renumbered + moved
}

/**
 * Where [taskId] belongs after a drag, given the list as it is now displayed ([tasks], the dragged
 * task already in its new place). Tasks that are not manually ordered in [order] are skipped (in
 * the default order the dated ones: they are listed by due date and their positions mean nothing).
 * Null when the task is not in the list or cannot be dragged. The plan itself is [planDropAmong].
 */
fun planDrop(tasks: List<Task>, taskId: Long, order: TaskOrder = TaskOrder.DEFAULT): DropPlan? {
    val index = tasks.indexOfFirst { it.id == taskId }
    if (index < 0 || !isManuallyOrdered(tasks[index], order)) return null
    return planDropAmong(
        tasks.filter { isManuallyOrdered(it, order) }.map { PositionedId(it.id, it.position) },
        taskId,
    )
}

/**
 * Where [movedId] belongs after a drag, given the items in the order they are now displayed
 * ([items], the dragged one already in its new place). The same plan the desktop app makes
 * (`planMove`):
 *
 * - normally one position, halfway between the items above and below (half of the first one at the
 *   top, one step past the last one at the end), and nothing else changes;
 * - when those two are equal or closer than [MIN_POSITION_GAP] there is no room: the middle of two
 *   equal numbers is the same number, so the drag would change nothing. Every item then gets a fresh
 *   position one [POSITION_STEP] apart in the displayed order, and only the ones whose position
 *   changes are listed.
 *
 * Null when [movedId] is not in [items].
 */
fun planDropAmong(items: List<PositionedId>, movedId: Long): DropPlan? {
    val index = items.indexOfFirst { it.id == movedId }
    if (index < 0) return null

    val above = if (index > 0) items[index - 1].position else 0.0
    val below = items.getOrNull(index + 1)?.position

    // At the end there is always room: one step past the last item, as new tasks are placed.
    if (below == null) return DropPlan(PositionUpdate(movedId, above + POSITION_STEP), emptyList())
    if (below - above >= MIN_POSITION_GAP) {
        return DropPlan(PositionUpdate(movedId, (above + below) / 2.0), emptyList())
    }

    var next = POSITION_STEP
    var movedPosition = next
    val renumbered = mutableListOf<PositionUpdate>()
    for (item in items) {
        val position = next
        next += POSITION_STEP
        if (item.id == movedId) {
            movedPosition = position
        } else if (item.position != position) {
            renumbered += PositionUpdate(item.id, position)
        }
    }
    return DropPlan(PositionUpdate(movedId, movedPosition), renumbered)
}
