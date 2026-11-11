package com.rendyhd.vicu.ui.screens.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.theme.LocalVicuColors

/** What the top row offers while something is carried over it, or nothing. */
internal enum class TopRowTarget {
    /** A block: dropping it here removes its time and keeps the day. */
    REMOVE_TIME,

    /** A task from the drawer: dropping it here plans it for the day without a time. */
    PLAN_FOR_DAY,
}

/**
 * The day's tasks without a time, above its timeline (contract 16.4): one line of chips that scrolls
 * sideways, the same chips as Today's Day view. A tap picks a task for a time, a press and hold drags
 * it, the circle ticks it. While a block or a drawer task is carried, the row is a drop target instead,
 * and it shows even on a day that has no such tasks. With [interactive] off (the day a swipe shows
 * before it lands) it only draws.
 */
@Composable
internal fun PlanTopRow(
    tasks: List<Task>,
    heldIds: Set<Long>,
    target: TopRowTarget?,
    targetHovered: Boolean,
    colorOf: (Task) -> Color,
    canDrag: (Task) -> Boolean,
    onPick: (Task) -> Unit,
    onToggleDone: (Task) -> Unit,
    onDragStart: (Task, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
) {
    if (tasks.isEmpty() && target == null) return
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics { contentDescription = "Tasks without a time" },
        contentAlignment = Alignment.CenterStart,
    ) {
        if (target != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(if (targetHovered) colors.primaryContainer else colors.surfaceContainer)
                    .border(
                        if (targetHovered) 2.dp else 1.dp,
                        colors.primary.copy(alpha = if (targetHovered) 1f else 0.6f),
                        MaterialTheme.shapes.small,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = when (target) {
                        TopRowTarget.REMOVE_TIME -> "Drop here to remove the time"
                        TopRowTarget.PLAN_FOR_DAY -> "Drop here to plan without a time"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (targetHovered) colors.onPrimaryContainer else colors.primary,
                )
            }
            return@Box
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "No time",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, end = 2.dp),
            )
            // A plain row, not a lazy one: a chip being dragged must stay composed while the day changes.
            tasks.forEach { task ->
                key(task.id) {
                    TopRowChip(
                        task = task,
                        done = task.done || task.id in heldIds,
                        color = colorOf(task),
                        canDrag = interactive && canDrag(task),
                        interactive = interactive,
                        onPick = { onPick(task) },
                        onToggleDone = { onToggleDone(task) },
                        onDragStart = onDragStart,
                        onDrag = onDrag,
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragCancel,
                    )
                }
            }
        }
    }
}

@Composable
private fun TopRowChip(
    task: Task,
    done: Boolean,
    color: Color,
    canDrag: Boolean,
    interactive: Boolean,
    onPick: () -> Unit,
    onToggleDone: () -> Unit,
    onDragStart: (Task, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val latestTask by rememberUpdatedState(task)
    Row(
        modifier = Modifier
            .heightIn(min = 40.dp)
            .clip(MaterialTheme.shapes.small)
            .background(colors.background)
            .border(1.dp, colors.outlineVariant, MaterialTheme.shapes.small)
            .onGloballyPositioned { coords = it }
            .then(if (interactive) Modifier.clickable(onClickLabel = "Pick a time") { onPick() } else Modifier)
            .then(
                if (canDrag) {
                    // Inside the click, so the end of a drag is no tap.
                    Modifier.pointerInput(task.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { at ->
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                coords?.let { onDragStart(latestTask, it.localToWindow(at)) }
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                coords?.let { onDrag(it.localToWindow(change.position)) }
                            },
                            onDragEnd = onDragEnd,
                            onDragCancel = onDragCancel,
                        )
                    }
                } else {
                    Modifier
                },
            )
            .padding(end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 40.dp)
                .then(
                    if (interactive) {
                        Modifier
                            .toggleable(value = done, role = Role.Checkbox, onValueChange = { onToggleDone() })
                            .semantics { contentDescription = if (done) "Mark ${task.title} not done" else "Complete ${task.title}" }
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (done) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (done) LocalVicuColors.current.done.color else color,
            )
        }
        Text(
            text = task.title,
            style = MaterialTheme.typography.labelLarge,
            textDecoration = if (done) TextDecoration.LineThrough else null,
            color = if (done) colors.onSurfaceVariant else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // One line, and no wider than about half a phone, so the next chip shows it is there.
            modifier = Modifier.widthIn(max = 180.dp),
        )
    }
}
