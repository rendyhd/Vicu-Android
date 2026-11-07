package com.rendyhd.vicu.ui.screens.taskdetail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.ui.components.task.MIN_TOUCH_TARGET
import com.rendyhd.vicu.ui.theme.VicuMotion

/**
 * The editor's properties (design concept 4): one grouped card of rows, a row per property that is
 * set, in the order of design-system-v1.md section 4.1. Rows are separated by a hairline that
 * starts after the icon.
 */
@Composable
internal fun PropertyGroup(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(content = { content() })
    }
}

/** The hairline between two [PropertyRow]s. */
@Composable
internal fun PropertyDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 52.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * One property: its icon, its name and its value in words. [value] null reads "None" (a row shown
 * only while its presets are open). [expandable] rows open presets in place ([open] turns the
 * arrow down); the others open a picker. [trailing] replaces the value text (label chips, avatars).
 * [emphasis] colours the value as overdue. The whole row is one 52 dp target.
 */
@Composable
internal fun PropertyRow(
    icon: ImageVector,
    name: String,
    value: String?,
    description: String,
    onClick: () -> Unit,
    expandable: Boolean = false,
    open: Boolean = false,
    emphasis: Boolean = false,
    enabled: Boolean = true,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                if (expandable) stateDescription = if (open) "Expanded" else "Collapsed"
            }
            .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(name, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface, maxLines = 1)
        Spacer(Modifier.width(12.dp))
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (trailing != null && value != null) {
                trailing()
            } else {
                Text(
                    text = value ?: "None",
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        emphasis -> scheme.error
                        value != null -> scheme.onSurface
                        else -> scheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                )
            }
        }
        if (enabled) {
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (expandable) Icons.Outlined.ExpandMore else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = scheme.outline,
                modifier = Modifier.size(20.dp).rotate(if (expandable && open) 180f else 0f),
            )
        }
    }
}

/**
 * The Labels row: every label, never "+N". While they all fit on one line they sit in the row, on
 * the right, like any other value. Otherwise they get lines of their own, starting under the name,
 * rather than wrapping in the row's narrow right half.
 */
@Composable
internal fun LabelsPropertyRow(
    icon: ImageVector,
    name: String,
    description: String,
    onClick: () -> Unit,
    chips: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Layout(
        contents = listOf(
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(name, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface, maxLines = 1)
                }
            },
            {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = scheme.outline,
                    modifier = Modifier.size(20.dp),
                )
            },
            chips,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
    ) { (heads, arrows, chipMeasurables), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val head = heads.single().measure(loose)
        val arrow = arrows.single().measure(loose)
        val width = constraints.maxWidth
        val gap = 6.dp.roundToPx()
        val nameStart = 36.dp.roundToPx()
        val placeables = chipMeasurables.map { it.measure(Constraints(maxWidth = (width - nameStart).coerceAtLeast(0))) }
        val widths = placeables.map { it.width }
        val inlineRoom = width - head.width - 12.dp.roundToPx() - 4.dp.roundToPx() - arrow.width
        val inline = widthOfOneLine(widths, gap) <= inlineRoom
        val lines = if (inline) emptyList() else wrapIntoLines(widths, gap, width - nameStart)
        val chipHeight = placeables.maxOfOrNull { it.height } ?: 0
        val topHeight = maxOf(head.height, arrow.height, if (inline) chipHeight else 0)
        val linesHeight = if (lines.isEmpty()) 0 else gap + lines.size * chipHeight + (lines.size - 1) * gap
        val height = maxOf(constraints.minHeight, topHeight + linesHeight)
        layout(width, height) {
            val top = if (lines.isEmpty()) (height - topHeight) / 2 else 0
            head.placeRelative(0, top + (topHeight - head.height) / 2)
            arrow.placeRelative(width - arrow.width, top + (topHeight - arrow.height) / 2)
            if (inline) {
                var x = width - arrow.width - 4.dp.roundToPx() - widthOfOneLine(widths, gap)
                placeables.forEach { chip ->
                    chip.placeRelative(x, top + (topHeight - chip.height) / 2)
                    x += chip.width + gap
                }
            } else {
                var y = topHeight + gap
                lines.forEach { line ->
                    var x = nameStart
                    line.forEach { index ->
                        placeables[index].placeRelative(x, y)
                        x += placeables[index].width + gap
                    }
                    y += chipHeight + gap
                }
            }
        }
    }
}

/** The width of [widths] side by side with [gap] between them. */
internal fun widthOfOneLine(widths: List<Int>, gap: Int): Int =
    widths.sum() + gap * (widths.size - 1).coerceAtLeast(0)

/** Splits [widths] into lines of at most [maxWidth], as indexes; an item wider than that has its own line. */
internal fun wrapIntoLines(widths: List<Int>, gap: Int, maxWidth: Int): List<List<Int>> {
    val lines = mutableListOf<MutableList<Int>>()
    var used = 0
    widths.forEachIndexed { index, width ->
        val current = lines.lastOrNull()
        if (current != null && used + gap + width <= maxWidth) {
            current += index
            used += gap + width
        } else {
            lines += mutableListOf(index)
            used = width
        }
    }
    return lines
}

/** The presets of an open [PropertyRow], under it and indented to its name. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RowPresets(visible: Boolean, content: @Composable () -> Unit) {
    // Springs follow the system animator scale: at scale 0 the row opens and closes at once.
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(VicuMotion.defaultSpatialSpec()) + fadeIn(VicuMotion.fastEffectsSpec()),
        exit = shrinkVertically(VicuMotion.defaultSpatialSpec()) + fadeOut(VicuMotion.fastEffectsSpec()),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(start = 52.dp, end = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) { content() }
    }
}

/**
 * A preset in an open row: tonal, filled with the secondary container when it is the current value.
 * [onRemove] adds a close mark (a reminder that is set). Drawn 36 dp tall in a 48 dp target.
 */
@Composable
internal fun PresetChip(
    label: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    icon: ImageVector? = null,
    danger: Boolean = false,
    onRemove: (() -> Unit)? = null,
    removeDescription: String = "Remove $label",
) {
    val scheme = MaterialTheme.colorScheme
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides MIN_TOUCH_TARGET) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = if (selected) scheme.secondaryContainer else scheme.surfaceContainerHighest,
            contentColor = when {
                danger -> scheme.error
                selected -> scheme.onSecondaryContainer
                else -> scheme.onSurface
            },
            modifier = Modifier.minimumInteractiveComponentSize().semantics {
                if (selected) stateDescription = "Selected"
            },
        ) {
            Row(
                modifier = Modifier.heightIn(min = 36.dp).padding(start = if (icon != null) 10.dp else 14.dp, end = if (onRemove != null) 4.dp else 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (onRemove != null) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = removeDescription,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable(role = Role.Button, onClick = onRemove)
                            .padding(5.dp),
                    )
                }
            }
        }
    }
}

/** An unset property in the editor's Add row: "+ Priority", quiet and tonal, never outlined. */
@Composable
internal fun AddPropertyChip(label: String, icon: ImageVector, description: String, onClick: () -> Unit, open: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides MIN_TOUCH_TARGET) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = if (open) scheme.secondaryContainer else scheme.surfaceContainer,
            contentColor = if (open) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
            modifier = Modifier.minimumInteractiveComponentSize().semantics { contentDescription = description },
        ) {
            Row(
                modifier = Modifier.heightIn(min = 34.dp).padding(start = 10.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}
