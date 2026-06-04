package com.rendyhd.vicu.ui.components.task

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.domain.model.Task

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

    val dismissState = rememberSwipeToDismissBoxState(
        // Require dragging half the row (was 0.35) to commit, so a quick flick near the
        // screen edge no longer triggers an accidental complete/schedule.
        positionalThreshold = { totalDistance -> totalDistance * 0.5f },
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> onToggleDone()
                SwipeToDismissBoxValue.EndToStart -> onSchedule()
                SwipeToDismissBoxValue.Settled -> {}
            }
            // Return false so the row springs back and stays visible (the undo pattern relies
            // on the row remaining) rather than dismissing.
            false
        },
    )

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

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        backgroundContent = {
            SwipeBackground(
                dismissDirection = dismissState.dismissDirection,
                progress = dismissState.progress,
            )
        },
        enableDismissFromStartToEnd = !task.done,
        enableDismissFromEndToStart = !task.done,
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
