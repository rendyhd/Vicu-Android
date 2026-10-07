package com.rendyhd.vicu.ui.components.task

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rendyhd.vicu.domain.repository.QuickDue
import com.rendyhd.vicu.ui.theme.PriorityHigh
import com.rendyhd.vicu.ui.theme.PriorityLow
import com.rendyhd.vicu.ui.theme.PriorityMedium
import com.rendyhd.vicu.ui.theme.PriorityUrgent

/** The smallest touch target Android accessibility guidance allows for something that is tapped. */
internal val MIN_TOUCH_TARGET: Dp = 48.dp

/**
 * What the screens give a task row so it can be scheduled without the swipe gesture. Provided
 * once at the app root; a row without a provider simply offers no schedule actions.
 */
interface TaskRowActions {
    /** Sets the due date of [taskId] to the end of [due]'s local day. */
    fun scheduleDue(taskId: Long, due: QuickDue)
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

/** One exclamation mark per priority level, so the level never depends on colour alone. Null when unset. */
internal fun priorityMarkText(priority: Int): String? = if (priority in 1..5) "!".repeat(priority) else null

/** The spoken form of a priority, or null when there is none. */
internal fun priorityDescription(priority: Int): String? = when (priority) {
    1 -> "Low priority"
    2 -> "Medium priority"
    3 -> "High priority"
    4 -> "Urgent priority"
    5 -> "Do now priority"
    else -> null
}

/**
 * The colour of a priority mark. The vivid colours of the dots read well on a dark surface; on a
 * light one the yellow and orange are too faint for text, so they are deepened.
 */
internal fun priorityMarkColor(priority: Int, dark: Boolean): Color? = when (priority) {
    1 -> if (dark) PriorityLow else Color(0xFF0066CC)
    2 -> if (dark) PriorityMedium else Color(0xFF9A6B00)
    3 -> if (dark) PriorityHigh else Color(0xFFB45309)
    4, 5 -> if (dark) PriorityUrgent else Color(0xFFD70015)
    else -> null
}

/** The priority of a task as text ("!" to "!!!!!") with its spoken form; nothing for no priority. */
@Composable
internal fun PriorityMark(priority: Int, modifier: Modifier = Modifier) {
    val text = priorityMarkText(priority) ?: return
    val description = priorityDescription(priority) ?: return
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Text(
        text = text,
        color = priorityMarkColor(priority, dark) ?: return,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        // The marks are read as the description, not as a run of exclamation points.
        modifier = modifier
            .padding(horizontal = 2.dp)
            .clearAndSetSemantics { contentDescription = description },
    )
}
