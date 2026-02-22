package com.rendyhd.vicu.ui.components.task

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.theme.PriorityHigh
import com.rendyhd.vicu.ui.theme.PriorityLow
import com.rendyhd.vicu.ui.theme.PriorityMedium
import com.rendyhd.vicu.ui.theme.PriorityUrgent
import com.rendyhd.vicu.util.DateUtils


@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskItem(
    task: Task,
    onToggleDone: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onSchedule: (() -> Unit)? = null,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            AnimatedCheckbox(
                done = task.done,
                onToggle = onToggleDone,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
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
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PriorityDot(priority = task.priority)
                TaskLinkIcons(description = task.description)
                if (task.repeatAfter > 0) {
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
                if (!DateUtils.isNullDate(task.dueDate) && task.dueDate.isNotBlank()) {
                    TaskDueBadge(dueDate = task.dueDate)
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 48.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
    }
}

@Composable
fun AnimatedCheckbox(
    done: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current

    // Fill progress: 0f = empty, 1f = fully filled
    val fillProgress = remember { Animatable(if (done) 1f else 0f) }
    // Checkmark reveal: 0f = hidden, 1f = fully drawn
    val checkProgress = remember { Animatable(if (done) 1f else 0f) }
    // Scale bounce: 1f = normal
    val scaleAnim = remember { Animatable(1f) }

    LaunchedEffect(done) {
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
            .size(24.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onToggle()
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(22.dp)) {
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
    val isOverdue = DateUtils.isOverdue(dueDate)
    val isToday = DateUtils.isToday(dueDate)
    val label = DateUtils.formatRelativeDate(dueDate)

    val bgColor = when {
        isOverdue -> Color(0xFFEF4444).copy(alpha = 0.12f)
        isToday -> Color(0xFFF97316).copy(alpha = 0.12f)
        else -> Color.Transparent
    }
    val textColor = when {
        isOverdue -> Color(0xFFEF4444)
        isToday -> Color(0xFFF97316)
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

@Composable
private fun PriorityDot(
    priority: Int,
    modifier: Modifier = Modifier,
) {
    val color = when (priority) {
        1 -> PriorityLow
        2 -> PriorityMedium
        3 -> PriorityHigh
        4 -> PriorityUrgent
        else -> return
    }
    val label = when (priority) {
        1 -> "Low priority"
        2 -> "Medium priority"
        3 -> "High priority"
        4 -> "Urgent priority"
        else -> return
    }
    Box(
        modifier = modifier
            .size(8.dp)
            .background(color, CircleShape)
            .semantics { contentDescription = label },
    )
}

@Composable
fun LabelChip(
    title: String,
    hexColor: String,
    modifier: Modifier = Modifier,
) {
    val chipColor = try {
        Color(android.graphics.Color.parseColor(if (hexColor.startsWith("#")) hexColor else "#$hexColor"))
    } catch (_: Exception) {
        MaterialTheme.colorScheme.primary
    }

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
