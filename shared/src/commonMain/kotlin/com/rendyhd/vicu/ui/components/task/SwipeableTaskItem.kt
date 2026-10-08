package com.rendyhd.vicu.ui.components.task

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.outlined.PriorityHigh
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import com.rendyhd.vicu.ui.theme.LocalVicuColors
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
import com.rendyhd.vicu.ui.theme.VicuMotion
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
    // The app opens the When sheet for a swipe to schedule; without it the row runs its own action.
    val rowActions = LocalTaskRowActions.current
    val currentSwipeSchedule by rememberUpdatedState<() -> Unit> {
        if (rowActions?.swipeSchedule(currentTask.id) != true) currentOnSchedule()
    }
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
                    SwipeToDismissBoxValue.EndToStart -> currentSwipeSchedule()
                    SwipeToDismissBoxValue.Settled -> {}
                }
            }
            // Always spring back: the undo pattern relies on the row remaining visible.
            false
        },
    )
    SideEffect { rowRefs.dismissState = dismissState }

    // One haptic per crossing of the commit point (edge-triggered via targetValue).
    LaunchedEffect(dismissState) {
        snapshotFlow { dismissState.targetValue }
            .collect { target ->
                if (target != SwipeToDismissBoxValue.Settled) {
                    haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
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
                offsetPx = runCatching { dismissState.requireOffset() }.getOrNull() ?: 0f,
                widthPx = rowRefs.widthPx,
                scheduleLabel = rowActions?.swipeScheduleLabel ?: "Schedule",
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

/**
 * What shows behind a row that is being swiped: the whole row tinted with the action's colour, a
 * little deeper the further it goes, and in the strip the row has uncovered an icon and a word
 * ("Complete", "Schedule"). At the commit point the strip turns to the full colour and the icon
 * pops (the pop spring); the same gesture plays GestureThresholdActivate (see the caller).
 * The distance is read from the row's offset, not from the state's progress, which reads 1 while
 * the row springs back to rest.
 */
@Composable
private fun SwipeBackground(
    dismissDirection: SwipeToDismissBoxValue,
    offsetPx: Float,
    widthPx: Float,
    scheduleLabel: String,
) {
    val colors = LocalVicuColors.current
    val surface = MaterialTheme.colorScheme.background
    val onSurface = MaterialTheme.colorScheme.onSurface
    // The direction and distance of the last frame that had a swipe: the background fades out from
    // there when the row has come back to rest, instead of vanishing.
    val last = remember { SwipeFrame() }
    if (dismissDirection != SwipeToDismissBoxValue.Settled) {
        if (last.atRest) {
            // A new swipe starts: forget the committed look of the last one.
            last.atRest = false
            last.committed = false
        }
        last.direction = dismissDirection
        last.fraction = if (widthPx > 0f) (abs(offsetPx) / widthPx).coerceIn(0f, 1f) else 0f
        // The swipe commits as it crosses the commit point and the row springs back at once: the
        // committed look (full colour, popped icon) is kept while it does, until the row is at rest.
        if (last.fraction >= SwipeTint.COMMIT) last.committed = true
    } else {
        last.atRest = true
    }
    val direction = last.direction
    val fraction = if (last.committed) maxOf(last.fraction, SwipeTint.COMMIT) else last.fraction
    val visible by animateFloatAsState(
        targetValue = if (dismissDirection == SwipeToDismissBoxValue.Settled) 0f else 1f,
        animationSpec = tween(VicuMotion.fadeFastMs),
        label = "swipeVisible",
    )
    if (direction == SwipeToDismissBoxValue.Settled || visible <= 0f) return

    val complete = direction == SwipeToDismissBoxValue.StartToEnd
    val role = if (complete) colors.swipeComplete else colors.swipeSchedule
    val armed = fraction >= SwipeTint.COMMIT
    val label = if (complete) "Complete" else scheduleLabel
    val icon = when {
        complete -> Icons.Outlined.Check
        scheduleLabel == "Schedule" -> Icons.Outlined.CalendarMonth
        else -> Icons.Outlined.PriorityHigh
    }

    // The icon springs up to size each time the swipe arms.
    val pop = remember { Animatable(1f) }
    LaunchedEffect(armed) {
        if (armed) {
            pop.snapTo(SWIPE_POP_FROM)
            pop.animateTo(1f, VicuMotion.pop.spec())
        } else {
            pop.snapTo(1f)
        }
    }

    // The tint follows the finger; the jump to the full colour at the commit point is a quick fade.
    val strip by animateColorAsState(
        targetValue = if (armed) role.color else Color.Transparent,
        animationSpec = VicuMotion.fastEffectsSpec(),
        label = "swipeStrip",
    )
    val labelColor by animateColorAsState(
        targetValue = SwipeTint.labelColor(role, surface, onSurface, fraction),
        animationSpec = VicuMotion.fastEffectsSpec(),
        label = "swipeLabel",
    )
    val stripWidth = with(LocalDensity.current) { abs(offsetPx).toDp() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(visible)
            .background(role.color.copy(alpha = SwipeTint.tintAlpha(fraction))),
    ) {
        Row(
            modifier = Modifier
                .align(if (complete) Alignment.CenterStart else Alignment.CenterEnd)
                .width(stripWidth)
                .fillMaxHeight()
                .clipToBounds()
                .background(strip)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (complete) Arrangement.Start else Arrangement.End,
        ) {
            if (complete) {
                SwipeIcon(icon, labelColor, pop.value)
                Spacer(Modifier.width(8.dp))
                SwipeWord(label, labelColor)
            } else {
                SwipeWord(label, labelColor)
                Spacer(Modifier.width(8.dp))
                SwipeIcon(icon, labelColor, pop.value)
            }
        }
    }
}

@Composable
private fun SwipeIcon(icon: ImageVector, tint: Color, scale: Float) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.scale(scale))
}

@Composable
private fun SwipeWord(text: String, color: Color) {
    Text(text = text, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1, softWrap = false)
}

/** The last frame of a swipe that had a direction, kept so the background can fade out after it and show the committed look while the row springs back. Not observable on purpose. */
private class SwipeFrame {
    var direction: SwipeToDismissBoxValue = SwipeToDismissBoxValue.Settled
    var fraction: Float = 0f
    var committed: Boolean = false
    var atRest: Boolean = true
}

/** The icon starts a commit this much smaller and springs to full size. */
private const val SWIPE_POP_FROM = 0.6f
