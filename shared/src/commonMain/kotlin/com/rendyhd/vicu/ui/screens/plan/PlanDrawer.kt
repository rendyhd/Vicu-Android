package com.rendyhd.vicu.ui.screens.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.rendyhd.vicu.ui.components.shared.LocalClockDay
import com.rendyhd.vicu.ui.components.shared.LocalDateFormat
import com.rendyhd.vicu.util.DateContext
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.util.PlanSource
import com.rendyhd.vicu.util.TimeBlocks
import com.rendyhd.vicu.util.parseHexColor
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime

/**
 * The tasks to plan, sliding in from the right: the same lists as the desktop's left panel. It opens
 * from the tab on the timeline (never from the screen edge, which is Back on Android). A short press
 * on a task picks it; press and hold drags it onto the timeline.
 */
@Composable
internal fun PlanDrawer(
    state: PlanUiState,
    sourceLabel: String,
    dayLabel: String,
    onClose: () -> Unit,
    onSource: (PlanSource) -> Unit,
    onFilter: (String) -> Unit,
    onPick: (Task) -> Unit,
    onDragStart: (Task, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.fillMaxHeight().semantics { contentDescription = "Tasks to plan" }) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(MaterialTheme.shapes.small)
                        .clickable(onClickLabel = "Change the list") { choosing = !choosing }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = sourceLabel,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = "  ${state.listedCount}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(
                        if (choosing) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "Close") }
            }

            if (choosing) {
                SourceList(
                    state = state,
                    dayLabel = dayLabel,
                    onChoose = {
                        onSource(it)
                        choosing = false
                    },
                    modifier = Modifier.weight(1f),
                )
                return@Column
            }

            OutlinedTextField(
                value = state.filter,
                onValueChange = onFilter,
                placeholder = { Text("Filter these tasks", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .semantics { contentDescription = "Filter these tasks" },
            )

            LazyColumn(
                modifier = Modifier.weight(1f).padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (state.listed.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = if (state.filter.isBlank()) "Nothing to plan here" else "Nothing with that name",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(state.listed, key = { it.id }, contentType = { "task" }) { task ->
                    PlanTaskRow(
                        task = task,
                        color = planProjectColor(state, task),
                        done = task.done,
                        canDrag = state.canChange(task),
                        selectedDay = state.selectedDay,
                        onPick = { onPick(task) },
                        onDragStart = onDragStart,
                        onDrag = onDrag,
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragCancel,
                    )
                }
            }

            HorizontalDivider()
            state.content?.let {
                Text(
                    text = planTotalsText(it.plannedMinutes, it.freeMinutes),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp),
                )
            }
            Text(
                text = "Tap a task to pick a time, or drag it onto the timeline.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** The colour of a task's project, for the bar on its row and its block. */
@Composable
internal fun planProjectColor(state: PlanUiState, task: Task): Color =
    parseHexColor(state.projects[task.projectId]?.hexColor.orEmpty()) ?: MaterialTheme.colorScheme.primary

@Composable
private fun PlanTaskRow(
    task: Task,
    color: Color,
    done: Boolean,
    canDrag: Boolean,
    selectedDay: LocalDate?,
    onPick: () -> Unit,
    onDragStart: (Task, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val clock = LocalClockDay.current
    val format = LocalDateFormat.current
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val latestTask by rememberUpdatedState(task)
    val meta = remember(task.dueDate, task.startDate, task.endDate, selectedDay, clock, format) {
        val block = TimeBlocks.blockOf(task, clock.zone)
        if (block == null && DueDates.localDateOf(task.dueDate, clock.zone) == selectedDay) {
            ""
        } else if (block != null) {
            DateDisplay.formatBlockRow(
                block.start.toLocalDateTime(clock.zone),
                block.end.toLocalDateTime(clock.zone),
                clock.date,
                format,
            )
        } else {
            DateDisplay.formatDue(DateContext.ROW, task.dueDate, clock.date, clock.zone, format)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .onGloballyPositioned { coords = it }
            .clickable(onClickLabel = "Pick a time") { onPick() }
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
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().heightIn(min = 48.dp).background(color))
        Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyMedium,
                textDecoration = if (done) TextDecoration.LineThrough else null,
                color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The four lists and every project with its subprojects, found by name. */
@Composable
private fun SourceList(
    state: PlanUiState,
    dayLabel: String,
    onChoose: (PlanSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val needle = query.trim().lowercase()
    val lists = PlanSource.lists(dayLabel).filter { it.second.lowercase().contains(needle) }
    val rows = state.projectRows
        .filter { it.project.title.lowercase().contains(needle) }
        // A match stands on its own: indented under a parent that is not listed, it would look lost.
        .map { if (needle.isEmpty()) it else it.copy(depth = 0) }

    Column(modifier = modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Find a list or project", style = MaterialTheme.typography.bodySmall) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .semantics { contentDescription = "Find a list or project" },
        )
        LazyColumn(modifier = Modifier.weight(1f).padding(top = 4.dp)) {
            if (lists.isNotEmpty()) item(key = "lists") { SourceHeader("Lists") }
            items(lists, key = { it.first.key }) { (source, label) ->
                SourceRow(label, depth = 0, dot = null, selected = state.source == source) { onChoose(source) }
            }
            if (rows.isNotEmpty()) item(key = "projects") { SourceHeader("Projects") }
            items(rows, key = { "project_${it.project.id}" }) { row ->
                SourceRow(
                    label = row.project.title,
                    depth = row.depth,
                    dot = parseHexColor(row.project.hexColor) ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    selected = state.source == PlanSource.Project(row.project.id),
                ) { onChoose(PlanSource.Project(row.project.id)) }
            }
            if (lists.isEmpty() && rows.isEmpty()) {
                item(key = "none") {
                    Text(
                        text = "Nothing with that name.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun SourceRow(label: String, depth: Int, dot: Color?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(start = 16.dp + 14.dp * depth, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Box(Modifier.width(8.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
