package com.rendyhd.vicu.ui.screens.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
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
 * The tasks to plan, sliding in from the right. Its header names the list on show with its count
 * ("Today 8") and opens the other lists, the same ones as the desktop's left panel; the filter is
 * behind the search button. It opens from the tab on the timeline (never from the screen edge, which
 * is Back on Android). A short press on a task picks it; press and hold drags it onto the timeline,
 * or onto a day of the strip. [listed] is what the list shows: during a drag, what it showed when the
 * drag began, so the row under the finger stays while the day changes.
 */
@Composable
internal fun PlanDrawer(
    state: PlanUiState,
    listed: List<Task>,
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
    var filtering by rememberSaveable { mutableStateOf(false) }
    // Counts the presses of the search button: each one puts the cursor in the filter.
    var focusFilter by remember { mutableIntStateOf(0) }
    val filterFocus = remember { FocusRequester() }
    // A filter still set from the last visit shows its field, or the list would be short for no reason.
    val showFilter = filtering || state.filter.isNotBlank()

    Surface(
        modifier = modifier.fillMaxHeight(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        Row(modifier = Modifier.fillMaxHeight()) {
            VerticalDivider()
            Column(modifier = Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = "Tasks to plan" }) {
                if (choosing) {
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { choosing = false }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to the tasks")
                        }
                        Text(
                            text = "Choose a project",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "Close") }
                    }
                    SourceList(
                        state = state,
                        onChoose = {
                            onSource(it)
                            choosing = false
                        },
                        modifier = Modifier.weight(1f),
                    )
                    return@Column
                }

                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SourcePicker(
                        state = state,
                        sourceLabel = sourceLabel,
                        dayLabel = dayLabel,
                        onSource = onSource,
                        onProject = { choosing = true },
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            filtering = true
                            focusFilter++
                        },
                    ) {
                        Icon(
                            Icons.Outlined.Search,
                            contentDescription = "Filter these tasks",
                            tint = if (showFilter) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                        )
                    }
                    IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "Close") }
                }

                if (showFilter) {
                    DrawerField(
                        value = state.filter,
                        onValueChange = onFilter,
                        placeholder = "Filter these tasks",
                        focusRequester = filterFocus,
                        clearLabel = "Stop filtering",
                        onClear = {
                            onFilter("")
                            filtering = false
                        },
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                    )
                    LaunchedEffect(focusFilter) {
                        if (focusFilter > 0) filterFocus.requestFocus()
                    }
                }

                LazyColumn(modifier = Modifier.weight(1f)) {
                    if (listed.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                text = if (state.filter.isBlank()) "Nothing to plan here" else "Nothing with that name",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                    items(listed, key = { it.id }, contentType = { "task" }) { task ->
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

                state.content?.let {
                    HorizontalDivider()
                    Text(
                        text = planTotalsText(it.plannedMinutes, it.freeMinutes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

/**
 * The drawer's header: the list on show and its count, and behind it the four lists with theirs and
 * the way to a project. One control, so the drawer opens on the tasks and not on a wall of chips.
 */
@Composable
private fun SourcePicker(
    state: PlanUiState,
    sourceLabel: String,
    dayLabel: String,
    onSource: (PlanSource) -> Unit,
    onProject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val count = state.sourceCounts[state.source.key] ?: state.listedCount
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(MaterialTheme.shapes.small)
                .clickable(onClickLabel = "Choose another list", role = Role.DropdownList) { open = true }
                .padding(start = 8.dp, end = 4.dp)
                .semantics { contentDescription = "Tasks from $sourceLabel, $count" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = sourceLabel,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(start = 6.dp),
            )
            Icon(
                if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp).size(20.dp),
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.widthIn(min = 208.dp),
        ) {
            PlanSource.lists(dayLabel).forEach { (source, label) ->
                val current = state.source == source
                DropdownMenuItem(
                    text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = {
                        if (current) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        } else {
                            Spacer(Modifier.size(20.dp))
                        }
                    },
                    trailingIcon = {
                        state.sourceCounts[source.key]?.let {
                            Text(
                                text = it.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        open = false
                        onSource(source)
                    },
                    modifier = Modifier.semantics { selected = current },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                // The header already names the project on show; this is the way to any other.
                text = { Text(if (state.source is PlanSource.Project) "Another project" else "A project") },
                leadingIcon = { Spacer(Modifier.size(20.dp)) },
                trailingIcon = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) },
                onClick = {
                    open = false
                    onProject()
                },
            )
        }
    }
}

/**
 * A text field the height of a row, for the drawer's two searches: a Material text field is 56 dp
 * with a label's room, too heavy for a panel this narrow. [onClear] adds a button that empties it.
 */
@Composable
private fun DrawerField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    clearLabel: String = "Clear",
    onClear: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        modifier = modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .semantics { contentDescription = placeholder },
        decorationBox = { field ->
            Row(
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceContainerHigh)
                    .padding(start = 14.dp, end = if (onClear != null) 0.dp else 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Search,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Box(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    field()
                }
                if (onClear != null) {
                    IconButton(onClick = onClear, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = clearLabel, modifier = Modifier.size(18.dp))
                    }
                }
            }
        },
    )
}

/** The colour of a task's project, for the dot on its row and its block. */
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
    // The line under the title, and whether it names a day that has passed.
    val (meta, late) = remember(task.dueDate, task.startDate, task.endDate, task.done, selectedDay, clock, format) {
        val block = TimeBlocks.blockOf(task, clock.zone)
        if (block == null && DueDates.localDateOf(task.dueDate, clock.zone) == selectedDay) {
            "" to false
        } else if (block != null) {
            DateDisplay.formatBlockRow(
                block.start.toLocalDateTime(clock.zone),
                block.end.toLocalDateTime(clock.zone),
                clock.date,
                format,
            ) to false
        } else {
            DateDisplay.formatDue(DateContext.ROW, task.dueDate, clock.date, clock.zone, format) to
                (!task.done && DueDates.isOverdue(task.dueDate, clock.date, clock.zone))
        }
    }
    Column {
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
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
                )
                .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
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
                        color = if (late) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Every project with its subprojects, found by name. The four lists are in the header's menu. */
@Composable
private fun SourceList(
    state: PlanUiState,
    onChoose: (PlanSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val needle = query.trim().lowercase()
    val rows = state.projectRows
        .filter { it.project.title.lowercase().contains(needle) }
        // A match stands on its own: indented under a parent that is not listed, it would look lost.
        .map { if (needle.isEmpty()) it else it.copy(depth = 0) }

    Column(modifier = modifier) {
        DrawerField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Find a project",
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        LazyColumn(modifier = Modifier.weight(1f).padding(top = 4.dp)) {
            if (rows.isNotEmpty()) item(key = "projects") { SourceHeader("Projects") }
            items(rows, key = { "project_${it.project.id}" }) { row ->
                SourceRow(
                    label = row.project.title,
                    depth = row.depth,
                    dot = parseHexColor(row.project.hexColor) ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    selected = state.source == PlanSource.Project(row.project.id),
                ) { onChoose(PlanSource.Project(row.project.id)) }
            }
            if (rows.isEmpty()) {
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
