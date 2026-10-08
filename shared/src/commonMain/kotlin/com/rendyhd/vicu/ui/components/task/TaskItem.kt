package com.rendyhd.vicu.ui.components.task

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.data.local.SubtaskDisplayMode
import com.rendyhd.vicu.domain.repository.QuickDue
import com.rendyhd.vicu.ui.components.shared.LocalClockDay
import com.rendyhd.vicu.ui.components.shared.LocalIs24Hour
import com.rendyhd.vicu.ui.theme.LocalVicuColors
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.RelationKind
import com.rendyhd.vicu.util.isRecurring
import com.rendyhd.vicu.util.TaskLinkParser
import com.rendyhd.vicu.util.parseHexColor
import com.rendyhd.vicu.util.subtaskProgress
import com.rendyhd.vicu.util.unfinishedDescendants


@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TaskItem(
    task: Task,
    onToggleDone: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selectionActive: Boolean = false,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onSubtaskToggleDone: (Task) -> Unit = {},
    onSubtaskClick: (Task) -> Unit = {},
    confirmRootCompletion: Boolean = true,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    val directSubtasks = task.relatedTasks[RelationKind.SUBTASK].orEmpty()
    // Walking the subtask tree and parsing the description are per-row work: done again only when
    // the thing they read changes, not on every recomposition while the list scrolls.
    val (completedSubtasks, subtaskCount) = remember(task.relatedTasks) { task.subtaskProgress() }
    val hasNotes = remember(task.description) { TaskLinkParser.hasNotesContent(task.description) }
    val displayMode = LocalSubtaskDisplayMode.current
    var subtasksExpanded by rememberSaveable(task.id) { mutableStateOf(false) }
    var pendingCompletion by remember { mutableStateOf<Task?>(null) }

    val requestToggle: (Task) -> Unit = { candidate ->
        val shouldConfirm = !candidate.done &&
            candidate.unfinishedDescendants().isNotEmpty() &&
            (candidate.id != task.id || confirmRootCompletion)
        if (shouldConfirm) {
            pendingCompletion = candidate
        } else if (candidate.id == task.id) {
            onToggleDone()
        } else {
            onSubtaskToggleDone(candidate)
        }
    }

    // A screen reader reaches complete, reopen and the quick due dates from the row's actions
    // menu: the swipe gestures are not available to it. While selecting, the row only selects.
    val rowActions = LocalTaskRowActions.current
    val customActions = if (selectionActive) {
        emptyList()
    } else {
        taskRowActions(
            done = task.done,
            canSchedule = rowActions != null,
            canMoveUp = onMoveUp != null,
            canMoveDown = onMoveDown != null,
        ).map { action ->
            action to {
                when (action) {
                    TaskRowAction.COMPLETE, TaskRowAction.REOPEN -> requestToggle(task)
                    TaskRowAction.DUE_TODAY -> rowActions?.scheduleDue(task.id, QuickDue.TODAY)
                    TaskRowAction.DUE_TOMORROW -> rowActions?.scheduleDue(task.id, QuickDue.TOMORROW)
                    TaskRowAction.MOVE_UP -> onMoveUp?.invoke()
                    TaskRowAction.MOVE_DOWN -> onMoveDown?.invoke()
                }
                Unit
            }
        }
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClickLabel = if (selectionActive) null else "Open",
                    onLongClickLabel = if (selectionActive) null else "Select",
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .taskRowCustomActions(customActions)
                .then(
                    if (selected) {
                        Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                    } else {
                        Modifier
                    },
                )
                // The checkbox brings its own 48 dp of height and 13 dp of space either side of
                // its circle, so the row's padding is only what is left of the old 16 dp.
                .padding(start = 4.dp, end = 16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (selectionActive) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.semantics { contentDescription = "Select ${task.title}" },
                )
            } else {
                AnimatedCheckbox(
                    done = task.done,
                    onToggle = { requestToggle(task) },
                    contentDescription = task.title,
                )
            }
            Column(modifier = Modifier.weight(1f).padding(vertical = 12.dp)) {
                if (task.labels.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        val displayed = task.labels.take(3)
                        displayed.forEach { label ->
                            LabelChip(title = label.title, hexColor = label.hexColor)
                        }
                        val overflow = task.labels.size - 3
                        if (overflow > 0) {
                            Text(
                                text = "+$overflow",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                }
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (task.done) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    textDecoration = if (task.done) TextDecoration.LineThrough else TextDecoration.None,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                task.relatedTasks[RelationKind.PARENTTASK].orEmpty().firstOrNull()?.let { parent ->
                    Text(
                        text = "Subtask of ${parent.title}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                // Level with the title's first line, whatever the checkbox's height does.
                modifier = Modifier.padding(top = 12.dp).heightIn(min = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TaskLinkIcons(description = task.description)
                if (hasNotes) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Notes,
                        contentDescription = "Has notes",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
                if (subtaskCount > 0) {
                    Row(
                        modifier = if (displayMode == SubtaskDisplayMode.EXPANDABLE) {
                            Modifier
                                .minimumInteractiveComponentSize()
                                .clickable(
                                    onClickLabel = if (subtasksExpanded) "Collapse subtasks" else "Expand subtasks",
                                    role = Role.Button,
                                ) { subtasksExpanded = !subtasksExpanded }
                        } else {
                            Modifier
                        },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (displayMode == SubtaskDisplayMode.EXPANDABLE) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                // The click label above says what a tap does.
                                contentDescription = null,
                                modifier = Modifier.size(18.dp).rotate(if (subtasksExpanded) 90f else 0f),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            imageVector = Icons.Outlined.Checklist,
                            contentDescription = "$completedSubtasks of $subtaskCount subtasks completed",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                        Text(
                            text = "$completedSubtasks/$subtaskCount",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (isRecurring(task.repeatAfter, task.repeatMode)) {
                    Icon(
                        imageVector = Icons.Outlined.Repeat,
                        contentDescription = "Repeating",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
                if (task.attachments.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Outlined.AttachFile,
                        contentDescription = "Attachments",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
                if (task.reminders.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Outlined.Notifications,
                        contentDescription = "Reminders",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
                PriorityMark(priority = task.priority)
                if (!DateUtils.isNullDate(task.dueDate) && task.dueDate.isNotBlank()) {
                    TaskDueBadge(dueDate = task.dueDate)
                }
            }
        }
        AnimatedVisibility(
            visible = displayMode == SubtaskDisplayMode.EXPANDABLE && subtasksExpanded,
        ) {
            InlineSubtaskTree(
                subtasks = directSubtasks,
                rootProjectId = task.projectId,
                depth = 1,
                onToggleDone = requestToggle,
                onClick = onSubtaskClick,
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 48.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
    }

    pendingCompletion?.let { candidate ->
        val count = candidate.unfinishedDescendants().size
        AlertDialog(
            onDismissRequest = { pendingCompletion = null },
            title = { Text("Complete task and subtasks?") },
            text = {
                Text(
                    "${if (count == 1) "One subtask is" else "$count subtasks are"} still open. " +
                        "Completing this task will complete ${if (count == 1) "it" else "them"} too.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingCompletion = null
                        if (candidate.id == task.id) onToggleDone() else onSubtaskToggleDone(candidate)
                    },
                ) {
                    Text("Complete all")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingCompletion = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun InlineSubtaskTree(
    subtasks: List<Task>,
    rootProjectId: Long,
    depth: Int,
    onToggleDone: (Task) -> Unit,
    onClick: (Task) -> Unit,
) {
    Column {
        subtasks.forEach { child ->
            val nested = child.relatedTasks[RelationKind.SUBTASK].orEmpty()
            val (completed, total) = remember(child.relatedTasks) { child.subtaskProgress() }
            var expanded by rememberSaveable(child.id) { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = "Open", onClick = { onClick(child) })
                    // The 48 dp checkbox brings 14 dp of space either side of its 20 dp circle.
                    .padding(start = (14 + depth.coerceAtMost(4) * 20).dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AnimatedCheckbox(
                    done = child.done,
                    onToggle = { onToggleDone(child) },
                    contentDescription = child.title,
                    circleSize = 20.dp,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = child.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (child.done) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        textDecoration = if (child.done) TextDecoration.LineThrough else null,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (child.projectId != 0L && child.projectId != rootProjectId) {
                        Text(
                            text = "Different project",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                PriorityMark(priority = child.priority)
                if (!DateUtils.isNullDate(child.dueDate) && child.dueDate.isNotBlank()) {
                    TaskDueBadge(dueDate = child.dueDate)
                }
                if (total > 0) {
                    Row(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clickable(
                                onClickLabel = if (expanded) "Collapse subtasks" else "Expand subtasks",
                                role = Role.Button,
                            ) { expanded = !expanded },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp).rotate(if (expanded) 90f else 0f),
                        )
                        Text(
                            text = "$completed/$total",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            AnimatedVisibility(visible = expanded) {
                InlineSubtaskTree(
                    subtasks = nested,
                    rootProjectId = rootProjectId,
                    depth = depth + 1,
                    onToggleDone = onToggleDone,
                    onClick = onClick,
                )
            }
        }
    }
}

/**
 * The round completion checkbox. Its touch target is [MIN_TOUCH_TARGET] square whatever the size of
 * the drawn circle ([circleSize]); to a screen reader it is a checkbox named [contentDescription]
 * (the task's title) that is checked when [done].
 */
@Composable
fun AnimatedCheckbox(
    done: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    circleSize: Dp = 22.dp,
) {
    val haptic = LocalHapticFeedback.current

    // Fill progress: 0f = empty, 1f = fully filled
    val fillProgress = remember { Animatable(if (done) 1f else 0f) }
    // Checkmark reveal: 0f = hidden, 1f = fully drawn
    val checkProgress = remember { Animatable(if (done) 1f else 0f) }
    // Scale bounce: 1f = normal
    val scaleAnim = remember { Animatable(1f) }
    // The state the checkbox was drawn in. A row that is composed already done (scrolling into
    // view, opening a screen) starts in its final state and must not play the completion bounce.
    var shownDone by remember { mutableStateOf(done) }

    LaunchedEffect(done) {
        if (done == shownDone) return@LaunchedEffect
        shownDone = done
        if (done) {
            // Animate in: fill → checkmark → bounce
            fillProgress.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
            checkProgress.animateTo(1f, tween(durationMillis = 300))
            scaleAnim.animateTo(1.15f, tween(durationMillis = 100))
            scaleAnim.animateTo(0.95f, tween(durationMillis = 100))
            scaleAnim.animateTo(1f, tween(durationMillis = 200))
        } else {
            // Animate out: instant reset
            scaleAnim.snapTo(1f)
            checkProgress.animateTo(0f, tween(durationMillis = 150))
            fillProgress.animateTo(0f, tween(durationMillis = 200))
        }
    }

    val outlineColor = MaterialTheme.colorScheme.outline
    val primaryColor = MaterialTheme.colorScheme.primary
    val fillColor by animateColorAsState(
        targetValue = if (done) primaryColor else Color.Transparent,
        animationSpec = tween(durationMillis = 300),
        label = "checkboxFill",
    )
    val checkColor = Color.White

    Box(
        modifier = modifier
            .size(MIN_TOUCH_TARGET)
            .toggleable(
                value = done,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Checkbox,
                onValueChange = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggle()
                },
            )
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(circleSize)) {
            val radius = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            val currentScale = scaleAnim.value
            val currentFill = fillProgress.value
            val currentCheck = checkProgress.value

            scale(currentScale) {
                // Draw border (always visible)
                drawCircle(
                    color = if (currentFill > 0.01f) fillColor else outlineColor,
                    radius = radius,
                    center = center,
                    style = Stroke(width = 1.5.dp.toPx()),
                )

                // Draw fill
                if (currentFill > 0.01f) {
                    drawCircle(
                        color = fillColor,
                        radius = radius * currentFill,
                        center = center,
                    )
                }

                // Draw checkmark
                if (currentCheck > 0.01f) {
                    val strokeWidth = 2.dp.toPx()
                    // Checkmark path: left-bottom to center-bottom to right-top
                    val p1 = Offset(size.width * 0.28f, size.height * 0.52f)
                    val p2 = Offset(size.width * 0.44f, size.height * 0.68f)
                    val p3 = Offset(size.width * 0.72f, size.height * 0.35f)

                    // First segment: p1 → p2
                    val seg1Progress = (currentCheck * 2f).coerceAtMost(1f)
                    if (seg1Progress > 0f) {
                        drawLine(
                            color = checkColor,
                            start = p1,
                            end = Offset(
                                p1.x + (p2.x - p1.x) * seg1Progress,
                                p1.y + (p2.y - p1.y) * seg1Progress,
                            ),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round,
                        )
                    }

                    // Second segment: p2 → p3
                    val seg2Progress = ((currentCheck - 0.5f) * 2f).coerceIn(0f, 1f)
                    if (seg2Progress > 0f) {
                        drawLine(
                            color = checkColor,
                            start = p2,
                            end = Offset(
                                p2.x + (p3.x - p2.x) * seg2Progress,
                                p2.y + (p3.y - p2.y) * seg2Progress,
                            ),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TaskDueBadge(
    dueDate: String,
    modifier: Modifier = Modifier,
) {
    // Reading LocalClockDay makes the badge recompose when the day or the time zone changes.
    val day = LocalClockDay.current
    val is24Hour = LocalIs24Hour.current
    val badge = remember(dueDate, day, is24Hour) {
        DueBadge(
            isOverdue = DateUtils.isOverdue(dueDate, day.date, day.zone),
            isToday = DateUtils.isToday(dueDate, day.date, day.zone),
            label = DateUtils.formatDueDate(dueDate, day.date, is24Hour, day.zone),
        )
    }
    val (isOverdue, isToday, label) = badge

    // Overdue is the error role on an 8% tint of itself; due today is the dueToday text colour
    // with no chip; later dates are quiet (docs/design-system-v1.md, status colours).
    val error = MaterialTheme.colorScheme.error
    val bgColor = if (isOverdue) error.copy(alpha = 0.08f) else Color.Transparent
    val textColor = when {
        isOverdue -> error
        isToday -> LocalVicuColors.current.dueToday.color
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Text(
        text = label,
        modifier = modifier
            .background(bgColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
        color = textColor,
        fontSize = 11.sp,
        maxLines = 1,
    )
}

/** What a due badge shows, worked out once per (date, day, clock style). */
private data class DueBadge(val isOverdue: Boolean, val isToday: Boolean, val label: String)

@Composable
fun LabelChip(
    title: String,
    hexColor: String,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val chipColor = remember(hexColor, primary) { parseHexColor(hexColor) ?: primary }

    Text(
        text = title,
        modifier = modifier
            .background(chipColor.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
        color = chipColor,
        fontSize = 11.sp,
        maxLines = 1,
    )
}
