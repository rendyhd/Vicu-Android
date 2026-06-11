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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
) {
    val haptic = LocalHapticFeedback.current

    // SwipeToDismissBox commits on fling velocity regardless of positionalThreshold, so a
    // quick flick could still trigger below the 50% mark. Track the live offset (Ref dance:
    // confirmValueChange is created before the state exists) and only fire when the row was
    // actually dragged at least half way.
    var rowWidthPx by remember { mutableStateOf(0f) }
    var dismissStateRef by remember { mutableStateOf<SwipeToDismissBoxState?>(null) }
    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { totalDistance -> totalDistance * 0.5f },
        confirmValueChange = { value ->
            val draggedFraction = dismissStateRef
                ?.let { state -> runCatching { abs(state.requireOffset()) }.getOrNull() }
                ?.let { offset -> if (rowWidthPx > 0f) offset / rowWidthPx else 1f }
                ?: 1f
            if (draggedFraction >= 0.5f) {
                when (value) {
                    SwipeToDismissBoxValue.StartToEnd -> onToggleDone()
                    SwipeToDismissBoxValue.EndToStart -> onSchedule()
                    SwipeToDismissBoxValue.Settled -> {}
                }
            }
            // Always spring back: the undo pattern relies on the row remaining visible.
            false
        },
    )
    SideEffect { dismissStateRef = dismissState }

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
            onToggleDone = onToggleDone,
            onClick = onClick,
            modifier = modifier.padding(start = contentStartPadding),
            selectionActive = selectionActive,
            selected = selected,
            onLongClick = onLongClick,
        )
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
    var gestureFromEdge by remember { mutableStateOf(false) }

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier
            .onSizeChanged { rowWidthPx = it.width.toFloat() }
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
            onToggleDone = onToggleDone,
            onClick = onClick,
            modifier = Modifier.padding(start = contentStartPadding),
            onLongClick = onLongClick,
        )
    }
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
