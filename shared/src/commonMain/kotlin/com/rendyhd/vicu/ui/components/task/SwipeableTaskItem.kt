package com.rendyhd.vicu.ui.components.task

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.components.section.ProjectMeta
import com.rendyhd.vicu.util.unfinishedDescendants
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeableTaskItem(
    task: Task,
    onToggleDone: () -> Unit,
    onClick: () -> Unit,
    onSchedule: () -> Unit,
    modifier: Modifier = Modifier,
    contentStartPadding: Dp = 0.dp,
    enabled: Boolean = true,
    selectionActive: Boolean = false,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onSubtaskToggleDone: (Task) -> Unit = {},
    onSubtaskClick: (Task) -> Unit = {},
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    /** The project to name on the meta line when the row's group has no header. */
    projectMeta: ProjectMeta? = null,
    /** What the view around the row already says (see [RowView]). */
    rowView: RowView? = null,
) {
    val haptic = LocalHapticFeedback.current
    var showCompletionConfirmation by remember { mutableStateOf(false) }

    // rememberSwipeToDismissBoxState keeps the state object (and the confirmValueChange it was
    // created with) for the row's whole lifetime, but the row stays composed while its task is
    // edited elsewhere. Everything the swipe handler touches is therefore read through State, so
    // a swipe always acts on the current task, subtask count and callbacks.
    val currentTask by rememberUpdatedState(task)
    val currentOnToggleDone by rememberUpdatedState(onToggleDone)
    val currentOnSchedule by rememberUpdatedState(onSchedule)
    val requestToggleDone: () -> Unit = {
        if (completionNeedsSubtaskConfirmation(currentTask)) {
            showCompletionConfirmation = true
        } else {
            currentOnToggleDone()
        }
    }
    val currentRequestToggleDone by rememberUpdatedState(requestToggleDone)

    // SwipeToDismissBox commits on fling velocity regardless of positionalThreshold, so a
    // quick flick could still trigger below the 50% mark. Track the live offset (Ref dance:
    // confirmValueChange is created before the state exists) and only fire when the row was
    // actually dragged at least half way. If the offset or row width is unavailable (cannot
    // happen in practice once a real drag has occurred), the action is suppressed rather
    // than fired.
    // The row width and the state are only read from the callback, never drawn: plain fields, so a
    // layout pass does not write observable state once per row.
    val rowRefs = remember { SwipeRowRefs() }
    var gestureFromEdge by remember { mutableStateOf(false) }
    // confirmValueChange is deprecated "without replacement", but nothing replaces what it does
    // here: the newer onDismiss callback runs after the row has settled on the dismissed side,
    // so the half-way check below (a flick must not commit early) and the spring-back would
    // both be lost. Moving to dynamic anchors is a rewrite of the gesture that needs checking
    // on a device, so the deprecated overload stays until then.
    @Suppress("DEPRECATION")
    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { totalDistance -> totalDistance * 0.5f },
        confirmValueChange = { value ->
            val draggedFraction = rowRefs.dismissState
                ?.let { state -> runCatching { abs(state.requireOffset()) }.getOrNull() }
                ?.let { offset -> if (rowRefs.widthPx > 0f) offset / rowRefs.widthPx else 0f }
                ?: 0f
            if (draggedFraction >= 0.5f) {
                when (value) {
                    SwipeToDismissBoxValue.StartToEnd -> currentRequestToggleDone()
                    SwipeToDismissBoxValue.EndToStart -> currentOnSchedule()
                    SwipeToDismissBoxValue.Settled -> {}
                }
            }
            // Always spring back: the undo pattern relies on the row remaining visible.
            false
        },
    )
    SideEffect { rowRefs.dismissState = dismissState }

    // One haptic per threshold crossing (edge-triggered via targetValue).
    LaunchedEffect(dismissState) {
        snapshotFlow { dismissState.targetValue }
            .collect { target ->
                if (target != SwipeToDismissBoxValue.Settled) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }
    }

    // Swipe is disabled while selecting (or when explicitly disabled): render the plain row,
    // which still carries the long-press-to-select and selection checkbox.
    if (!enabled || selectionActive) {
        TaskItem(
            task = task,
            onToggleDone = requestToggleDone,
            onClick = onClick,
            modifier = modifier.padding(start = contentStartPadding),
            selectionActive = selectionActive,
            selected = selected,
            onLongClick = onLongClick,
            onSubtaskToggleDone = onSubtaskToggleDone,
            onSubtaskClick = onSubtaskClick,
            confirmRootCompletion = false,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            projectMeta = projectMeta,
            rowView = rowView,
        )
        if (showCompletionConfirmation) {
            CompletionConfirmationDialog(
                unfinishedSubtaskCount = currentTask.unfinishedDescendants().size,
                onConfirm = {
                    showCompletionConfirmation = false
                    currentOnToggleDone()
                },
                onDismiss = { showCompletionConfirmation = false },
            )
        }
        return
    }

    // Edge dead-zone: gestures that begin inside the system-gesture insets (24dp minimum)
    // belong to OS back / the nav drawer, not the row swipe. The dismiss directions are
    // disabled for that gesture instead of consuming its events, so the drawer edge-swipe
    // still works on top of task rows.
    val layoutDirection = LocalLayoutDirection.current
    val gestureInsets = WindowInsets.systemGestures.asPaddingValues()
    val leftDeadZone = max(gestureInsets.calculateLeftPadding(layoutDirection), 24.dp)
    val rightDeadZone = max(gestureInsets.calculateRightPadding(layoutDirection), 24.dp)

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier
            .onSizeChanged { rowRefs.widthPx = it.width.toFloat() }
            .pointerInput(leftDeadZone, rightDeadZone) {
                val leftPx = leftDeadZone.toPx()
                val rightPx = rightDeadZone.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                    gestureFromEdge = down.position.x < leftPx ||
                        down.position.x > size.width - rightPx
                }
            },
        backgroundContent = {
            SwipeBackground(
                dismissDirection = dismissState.dismissDirection,
                progress = dismissState.progress,
            )
        },
        enableDismissFromStartToEnd = !task.done && !gestureFromEdge,
        enableDismissFromEndToStart = !task.done && !gestureFromEdge,
    ) {
        TaskItem(
            task = task,
            onToggleDone = requestToggleDone,
            onClick = onClick,
            modifier = Modifier.padding(start = contentStartPadding),
            onLongClick = onLongClick,
            onSubtaskToggleDone = onSubtaskToggleDone,
            onSubtaskClick = onSubtaskClick,
            confirmRootCompletion = false,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            projectMeta = projectMeta,
            rowView = rowView,
        )
    }

    if (showCompletionConfirmation) {
        CompletionConfirmationDialog(
            unfinishedSubtaskCount = currentTask.unfinishedDescendants().size,
            onConfirm = {
                showCompletionConfirmation = false
                currentOnToggleDone()
            },
            onDismiss = { showCompletionConfirmation = false },
        )
    }
}

/** The row's width and swipe state, read by the swipe callback only. Not observable on purpose. */
private class SwipeRowRefs {
    var widthPx: Float = 0f
    var dismissState: SwipeToDismissBoxState? = null
}

/**
 * Completing an open task that still has unfinished subtasks asks first, because completing the
 * parent completes them too. Decided on the task as it is now, never on a remembered copy.
 */
internal fun completionNeedsSubtaskConfirmation(task: Task): Boolean =
    !task.done && task.unfinishedDescendants().isNotEmpty()

@Composable
private fun CompletionConfirmationDialog(
    unfinishedSubtaskCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Complete task and subtasks?") },
        text = {
            Text(
                "${if (unfinishedSubtaskCount == 1) "One subtask is" else "$unfinishedSubtaskCount subtasks are"} " +
                    "still open. Completing this task will complete " +
                    "${if (unfinishedSubtaskCount == 1) "it" else "them"} too.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Complete all") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun SwipeBackground(
    dismissDirection: SwipeToDismissBoxValue,
    progress: Float,
) {
    // M3 color roles instead of hardcoded iOS green/orange, so the swipe backgrounds match
    // the dynamic theme. Neither action is destructive, so NOT errorContainer.
    val completeBg = MaterialTheme.colorScheme.tertiaryContainer
    val onCompleteBg = MaterialTheme.colorScheme.onTertiaryContainer
    val scheduleBg = MaterialTheme.colorScheme.secondaryContainer
    val onScheduleBg = MaterialTheme.colorScheme.onSecondaryContainer

    val bgColor by animateColorAsState(
        targetValue = when (dismissDirection) {
            SwipeToDismissBoxValue.StartToEnd -> completeBg
            SwipeToDismissBoxValue.EndToStart -> scheduleBg
            SwipeToDismissBoxValue.Settled -> Color.Transparent
        },
        animationSpec = tween(200),
        label = "swipeBg",
    )

    // Reveal the action icon only once the drag is far enough to commit, so the background
    // check doesn't collide with the row's own checkbox early in the gesture.
    val showIcon = progress >= 0.5f

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = when (dismissDirection) {
            SwipeToDismissBoxValue.EndToStart -> Arrangement.End
            else -> Arrangement.Start
        },
    ) {
        if (showIcon) {
            when (dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd ->
                    Icon(Icons.Outlined.Check, contentDescription = "Complete", tint = onCompleteBg)
                SwipeToDismissBoxValue.EndToStart ->
                    Icon(Icons.Outlined.CalendarMonth, contentDescription = "Schedule", tint = onScheduleBg)
                SwipeToDismissBoxValue.Settled -> {}
            }
        }
    }
}
