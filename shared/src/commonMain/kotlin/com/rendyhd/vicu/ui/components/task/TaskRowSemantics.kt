package com.rendyhd.vicu.ui.components.task

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.domain.repository.QuickDue
import com.rendyhd.vicu.ui.theme.VicuColors

/** The smallest touch target Android accessibility guidance allows for something that is tapped. */
internal val MIN_TOUCH_TARGET: Dp = 48.dp

/**
 * What the screens give a task row so it can be scheduled without the swipe gesture. Provided
 * once at the app root; a row without a provider simply offers no schedule actions.
 */
interface TaskRowActions {
    /** Sets the due date of [taskId] to the end of [due]'s local day. */
    fun scheduleDue(taskId: Long, due: QuickDue)

    /**
     * A swipe to schedule on [taskId]. Returns true when the app took it (it opens the When sheet),
     * false when the row should run its own configured action (the "Urgent" swipe setting).
     */
    fun swipeSchedule(taskId: Long): Boolean

    /** The word beside the icon of a swipe to schedule: "Schedule" when it opens the When sheet, "Urgent" for the other setting. */
    val swipeScheduleLabel: String get() = "Schedule"
}

val LocalTaskRowActions = staticCompositionLocalOf<TaskRowActions?> { null }

/** The things a screen reader can do to a task row besides the row's own tap (open) and long press (select). */
internal enum class TaskRowAction(val label: String) {
    COMPLETE("Complete"),
    REOPEN("Mark as not done"),
    DUE_TODAY("Due today"),
    DUE_TOMORROW("Due tomorrow"),
    MOVE_UP("Move up"),
    MOVE_DOWN("Move down"),
}

/** Complete or reopen always; scheduling only for an open task, and only when something can do it. */
internal fun taskRowActions(
    done: Boolean,
    canSchedule: Boolean,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
): List<TaskRowAction> = buildList {
    add(if (done) TaskRowAction.REOPEN else TaskRowAction.COMPLETE)
    if (!done && canSchedule) {
        add(TaskRowAction.DUE_TODAY)
        add(TaskRowAction.DUE_TOMORROW)
    }
    // The drag has no equivalent for a screen reader: these are the same move, one place at a time.
    if (canMoveUp) add(TaskRowAction.MOVE_UP)
    if (canMoveDown) add(TaskRowAction.MOVE_DOWN)
}

/**
 * TalkBack "Move up" / "Move down" for a row that can be dragged to reorder (a drawer entry, say).
 * A null callback means the move is not possible from there, and its action is not offered.
 */
internal fun Modifier.moveCustomActions(onMoveUp: (() -> Unit)?, onMoveDown: (() -> Unit)?): Modifier =
    taskRowCustomActions(
        listOfNotNull(
            onMoveUp?.let { TaskRowAction.MOVE_UP to it },
            onMoveDown?.let { TaskRowAction.MOVE_DOWN to it },
        ),
    )

/** TalkBack custom actions for the row: each entry of [actions] runs its lambda. */
internal fun Modifier.taskRowCustomActions(actions: List<Pair<TaskRowAction, () -> Unit>>): Modifier =
    if (actions.isEmpty()) {
        this
    } else {
        semantics {
            customActions = actions.map { (action, run) ->
                CustomAccessibilityAction(action.label) {
                    run()
                    true
                }
            }
        }
    }

/** The spoken form of a priority, or null when there is none. */
internal fun priorityDescription(priority: Int): String? = when (priority) {
    1 -> "Low priority"
    2 -> "Medium priority"
    3 -> "High priority"
    4 -> "Urgent priority"
    5 -> "Do now priority"
    else -> null
}

/** The colour of a priority mark: the priority role of the theme (4 and 5 are urgent), or null for none. */
internal fun priorityMarkColor(priority: Int, colors: VicuColors): Color? = colors.priority(priority)
