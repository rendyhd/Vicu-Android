package com.rendyhd.vicu.ui.components.task

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.domain.model.Task

private val CompleteGreen = Color(0xFF34C759)
private val CompleteGreenBg = Color(0xFF34C759)
private val ScheduleOrange = Color(0xFFFF9800)
private val ScheduleOrangeBg = Color(0xFFFF9800)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeableTaskItem(
    task: Task,
    onToggleDone: () -> Unit,
    onClick: () -> Unit,
    onSchedule: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current

    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { totalDistance -> totalDistance * 0.35f },
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    // Swipe right → complete
                    onToggleDone()
                    false // reset to settled so row stays visible (undo pattern)
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    // Swipe left → schedule
                    onSchedule()
                    false // reset, date picker opens
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        },
    )

    // Fire a single haptic when a swipe crosses the threshold into a dismiss
    // direction. Driving this from targetValue (edge-triggered, one emission per
    // gesture) instead of from confirmValueChange — which re-runs every frame while
    // a swipe is held past the threshold — fixes the rapid repeated vibration on
    // the right-to-left (schedule) swipe reported in issue #6.
    LaunchedEffect(dismissState) {
        snapshotFlow { dismissState.targetValue }
            .collect { target ->
                if (target != SwipeToDismissBoxValue.Settled) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }
    }

    if (!enabled) {
        TaskItem(
            task = task,
            onToggleDone = onToggleDone,
            onClick = onClick,
            modifier = modifier,
        )
        return
    }

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        backgroundContent = {
            SwipeBackground(dismissState.dismissDirection)
        },
        enableDismissFromStartToEnd = !task.done,
        enableDismissFromEndToStart = !task.done,
    ) {
        TaskItem(
            task = task,
            onToggleDone = onToggleDone,
            onClick = onClick,
        )
    }
}

@Composable
private fun SwipeBackground(
    dismissDirection: SwipeToDismissBoxValue,
) {
    val bgColor by animateColorAsState(
        targetValue = when (dismissDirection) {
            SwipeToDismissBoxValue.StartToEnd -> CompleteGreenBg
            SwipeToDismissBoxValue.EndToStart -> ScheduleOrangeBg
            SwipeToDismissBoxValue.Settled -> Color.Transparent
        },
        animationSpec = tween(200),
        label = "swipeBg",
    )

    val iconTint = Color.White

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = when (dismissDirection) {
            SwipeToDismissBoxValue.StartToEnd -> Arrangement.Start
            SwipeToDismissBoxValue.EndToStart -> Arrangement.End
            SwipeToDismissBoxValue.Settled -> Arrangement.Start
        },
    ) {
        when (dismissDirection) {
            SwipeToDismissBoxValue.StartToEnd -> {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = "Complete",
                    tint = iconTint,
                )
            }
            SwipeToDismissBoxValue.EndToStart -> {
                Icon(
                    imageVector = Icons.Outlined.CalendarMonth,
                    contentDescription = "Schedule",
                    tint = iconTint,
                )
            }
            SwipeToDismissBoxValue.Settled -> {}
        }
    }
}
