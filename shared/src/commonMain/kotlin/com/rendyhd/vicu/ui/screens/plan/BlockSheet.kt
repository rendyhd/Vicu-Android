package com.rendyhd.vicu.ui.screens.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.components.picker.MonthCalendar
import com.rendyhd.vicu.ui.components.shared.LocalClockDay
import com.rendyhd.vicu.ui.components.shared.LocalDateFormat
import com.rendyhd.vicu.ui.components.shared.VicuDragHandle
import com.rendyhd.vicu.util.DateContext
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.util.PlannerTimeline
import com.rendyhd.vicu.util.TimeBlocks
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/** The exact lengths a block's sheet offers: the default lengths and two hours, as on the desktop. */
internal val BlockSheetLengths: List<Int> = TimeBlocks.BLOCK_LENGTHS + 120

/** How many days from today the Day row offers as chips, as on the desktop; Pick a date reaches the rest. */
internal const val BLOCK_SHEET_DAYS = 6

/**
 * A tap on a block: another day, exact lengths, nudges, Remove time and Open task. A task with only a due time can
 * be given a block here. Someone who can only view gets just Open task (contract 16.8), and a block
 * on a past day cannot change its time, only go to another day or lose its time.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun BlockSheet(
    task: Task,
    canChange: Boolean,
    canPlace: Boolean,
    defaultLength: Int,
    onSetLength: (Int) -> Unit,
    onNudge: (Int) -> Unit,
    onMoveToDay: ((LocalDate) -> Unit)?,
    onRemoveTime: () -> Unit,
    onAddBlock: () -> Unit,
    onOpenTask: () -> Unit,
    onDismiss: () -> Unit,
    onToggleDone: (() -> Unit)? = null,
) {
    val clock = LocalClockDay.current
    val format = LocalDateFormat.current
    val block = TimeBlocks.blockOf(task, clock.zone)
    val hasDueTime = !DueDates.isDateOnly(task.dueDate, clock.zone) && DueDates.localDateOf(task.dueDate, clock.zone) != null
    var pickingDay by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { VicuDragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (onToggleDone != null && canChange) {
                // A block too short for a check on the grid has it here.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(value = task.done, role = Role.Checkbox, onValueChange = { onToggleDone() }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = task.done, onCheckedChange = null, modifier = Modifier.padding(end = 12.dp))
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (block != null) {
                val range = DateDisplay.formatBlockRange(
                    block.start.toLocalDateTime(clock.zone),
                    block.end.toLocalDateTime(clock.zone),
                    format,
                )
                Text(
                    text = "$range · ${PlannerTimeline.formatLength(block.minutes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (hasDueTime) {
                Text(
                    text = "Due ${DateDisplay.formatDue(DateContext.ROW, task.dueDate, clock.date, clock.zone, format)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (block != null && canChange && onMoveToDay != null && pickingDay) {
                // Pick a date: the calendar takes the sheet until a day is chosen.
                Text(
                    text = "Move to a day",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                MonthCalendar(
                    selected = block.day,
                    onSelect = { day ->
                        if (PlannerTimeline.canPlaceOn(day, clock.date)) onMoveToDay(day)
                    },
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { pickingDay = false }) { Text("Cancel") }
                }
                return@Column
            }

            if (block != null && canChange && onMoveToDay != null) {
                // The same times on another day (contract 16.2); the next days, then any date.
                Text(
                    text = "Day",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                // Wrapped like the lengths, so Pick a date is always in sight.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (offset in 0 until BLOCK_SHEET_DAYS) {
                        val day = clock.date.plus(offset, DateTimeUnit.DAY)
                        val label = if (offset < 2) {
                            DateDisplay.formatDay(DateContext.CHIP, day, clock.date, format)
                        } else {
                            DateDisplay.formatWeekdayDay(day, format)
                        }
                        FilterChip(
                            selected = block.day == day,
                            onClick = { if (block.day != day) onMoveToDay(day) },
                            label = { Text(label) },
                            modifier = Modifier.semantics {
                                contentDescription = "Move to ${DateDisplay.formatWeekdayDate(day, clock.date, format)}"
                            },
                        )
                    }
                    FilterChip(
                        selected = false,
                        onClick = { pickingDay = true },
                        label = { Text("Pick a date") },
                        leadingIcon = { Icon(Icons.Outlined.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }
            }

            if (block != null && canChange && canPlace) {
                Text(
                    text = "Length",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BlockSheetLengths.forEach { minutes ->
                        FilterChip(
                            selected = block.minutes == minutes,
                            onClick = { onSetLength(minutes) },
                            label = { Text(PlannerTimeline.formatLength(minutes)) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onSetLength(maxOf(TimeBlocks.STEP_MINUTES, block.minutes - TimeBlocks.STEP_MINUTES)) },
                        modifier = Modifier.semantics { contentDescription = "15 minutes shorter" },
                    ) { Text("Shorter") }
                    OutlinedButton(
                        onClick = { onSetLength(block.minutes + TimeBlocks.STEP_MINUTES) },
                        modifier = Modifier.semantics { contentDescription = "15 minutes longer" },
                    ) { Text("Longer") }
                }
                Text(
                    text = "Move",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onNudge(-TimeBlocks.STEP_MINUTES) },
                        modifier = Modifier.semantics { contentDescription = "15 minutes earlier" },
                    ) { Text("Earlier") }
                    OutlinedButton(
                        onClick = { onNudge(TimeBlocks.STEP_MINUTES) },
                        modifier = Modifier.semantics { contentDescription = "15 minutes later" },
                    ) { Text("Later") }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                if (block == null && hasDueTime && canChange && canPlace) {
                    TextButton(onClick = onAddBlock) {
                        Text("Add ${PlannerTimeline.formatLength(defaultLength)} block")
                    }
                }
                if (block != null && canChange) {
                    TextButton(onClick = onRemoveTime) { Text("Remove time") }
                }
                TextButton(onClick = onOpenTask) { Text("Open task") }
            }
        }
    }
}
