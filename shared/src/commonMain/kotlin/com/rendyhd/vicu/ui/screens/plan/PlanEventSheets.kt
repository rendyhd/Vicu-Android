package com.rendyhd.vicu.ui.screens.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.ui.components.picker.CustomTimeDialog
import com.rendyhd.vicu.ui.components.picker.MonthCalendar
import com.rendyhd.vicu.ui.components.shared.LocalClockDay
import com.rendyhd.vicu.ui.components.shared.LocalDateFormat
import com.rendyhd.vicu.ui.components.shared.VicuDragHandle
import com.rendyhd.vicu.util.CalendarEvent
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.EditValue
import com.rendyhd.vicu.util.EventEdit
import com.rendyhd.vicu.util.EventRepeat
import com.rendyhd.vicu.util.EventScope
import com.rendyhd.vicu.util.GestureRange
import com.rendyhd.vicu.util.ListedEvent
import com.rendyhd.vicu.util.PlanEvents
import com.rendyhd.vicu.util.PlannerTimeline
import com.rendyhd.vicu.util.RepeatChoice
import com.rendyhd.vicu.util.TimeBlocks
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/** The lengths an Event's sheet offers, as on the desktop's popover. */
internal val EventSheetLengths: List<Int> = listOf(15, 30, 45, 60, 90, 120, 180, 240)

/** What is made on an empty time: a task with a block, or an Event (contract 20.3). */
internal enum class NewOnPlanKind { TASK, EVENT }

/**
 * A press and hold on an empty time: name what goes there. A task (the default) takes a block in the
 * Inbox; an Event is a time you are not available, which can repeat (choices of the day, contract
 * 20.5), shows on Plan only and counts as busy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewOnPlanSheet(
    day: LocalDate,
    startMinute: Int,
    defaultLength: Int,
    canAddTask: Boolean,
    startAsEvent: Boolean = false,
    onTask: (String) -> Unit,
    onEvent: (String, EventRepeat) -> Unit,
    onDismiss: () -> Unit,
) {
    val format = LocalDateFormat.current
    val zone = LocalClockDay.current.zone
    // A Boolean, not the enum: saved UI state on iOS takes only the types its codec knows.
    var isEvent by rememberSaveable { mutableStateOf(!canAddTask || startAsEvent) }
    val kind = if (isEvent) NewOnPlanKind.EVENT else NewOnPlanKind.TASK
    var title by rememberSaveable { mutableStateOf("") }
    var choice by rememberSaveable { mutableStateOf(0) }
    val choices = remember(day) { PlanEvents.repeatChoices(day) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val range = GestureRange(startMinute, minOf(PlannerTimeline.DAY_MINUTES, startMinute + defaultLength))

    fun submit() {
        val text = title.trim()
        if (text.isEmpty()) return
        if (kind == NewOnPlanKind.TASK) onTask(text) else onEvent(text, choices[choice].repeat)
    }

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
                .padding(bottom = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Add as" }) {
                NewOnPlanKind.entries.forEachIndexed { index, value ->
                    SegmentedButton(
                        selected = kind == value,
                        onClick = { isEvent = value == NewOnPlanKind.EVENT },
                        enabled = value == NewOnPlanKind.EVENT || canAddTask,
                        shape = SegmentedButtonDefaults.itemShape(index, NewOnPlanKind.entries.size),
                        label = { Text(if (value == NewOnPlanKind.TASK) "Task" else "Event") },
                    )
                }
            }
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(if (kind == NewOnPlanKind.TASK) "Task title" else "Event title") },
                placeholder = { if (kind == NewOnPlanKind.EVENT) Text("Such as Manager call") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Text(
                text = rangeText(day, range, zone, format) + if (kind == NewOnPlanKind.TASK) {
                    " · in the Inbox"
                } else {
                    " · not a task: shows on Plan and counts as busy"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (kind == NewOnPlanKind.EVENT) {
                RepeatPicker(choices = choices, selected = choice, onSelect = { choice = it })
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = ::submit, enabled = title.isNotBlank()) {
                    Text(if (kind == NewOnPlanKind.TASK) "Add task" else "Add event")
                }
            }
        }
    }
}

/**
 * The repeat choices as a list of radio rows; [extra] is a rule the list does not offer, shown first.
 * With [scope] (a repeating Event), the open list starts with which times the new repeat is for, All
 * times or This and following, because the choices are those of the day the rule then starts on
 * (contract 20.6).
 */
@Composable
private fun RepeatPicker(
    choices: List<RepeatChoice>,
    selected: Int,
    onSelect: (Int) -> Unit,
    extra: String? = null,
    scope: EventScope? = null,
    onScope: (EventScope) -> Unit = {},
) {
    var open by remember { mutableStateOf(false) }
    val current = if (selected >= 0) choices[selected].label else extra.orEmpty()
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClickLabel = if (open) "Close the repeat choices" else "Choose how it repeats") { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Repeat", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(current, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (open) {
            if (scope != null) {
                val scopes = listOf(EventScope.ALL to "All times", EventScope.FOLLOWING to "This and following")
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).semantics { contentDescription = "Repeat change for" },
                ) {
                    scopes.forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = scope == value,
                            onClick = { onScope(value) },
                            shape = SegmentedButtonDefaults.itemShape(index, scopes.size),
                            label = { Text(label) },
                        )
                    }
                }
            }
            Column(Modifier.selectableGroup()) {
                if (selected < 0 && extra != null) RadioRow(extra, true) {}
                choices.forEachIndexed { index, item ->
                    RadioRow(item.label, index == selected) {
                        onSelect(index)
                        open = false
                    }
                }
            }
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(end = 12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * A pressed event. Your own (contract 20.6): title, all day, time and length, the day, repeat, ends
 * on and counts as busy, written together by Save; Skip this one and Delete at once. A phone
 * calendar's is read only: title, time, place and calendar (20.7).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun EventSheet(
    occurrence: CalendarEvent,
    own: ListedEvent?,
    calendarName: String,
    defaultLength: Int,
    /** The change, and which times it is for when the sheet already settled it (a new repeat). */
    onSave: (EventEdit, EventScope?) -> Unit,
    onSkip: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val format = LocalDateFormat.current
    val clock = LocalClockDay.current
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
                .padding(bottom = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (own == null) {
                Text(occurrence.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(
                    text = listOfNotNull(eventTimeText(occurrence, clock.zone, format), occurrence.location).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = listOfNotNull(
                        "Tentative".takeIf { occurrence.tentative },
                        "Free".takeIf { !occurrence.busy },
                        "From $calendarName, read only",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                return@Column
            }

            val event = own.event
            val occurrenceDay = occurrence.day ?: event.start
            var title by remember(own) { mutableStateOf(event.title) }
            var allDay by remember(own) { mutableStateOf(event.time == null) }
            var time by remember(own) { mutableStateOf(event.time ?: "09:00") }
            var minutes by remember(own) { mutableStateOf(if (event.minutes > 0) event.minutes else defaultLength) }
            var day by remember(own) { mutableStateOf(occurrenceDay) }
            val repeats = event.repeat != EventRepeat.None
            // Which times a new repeat is for; its choices are those of the day the rule starts on (20.6).
            var repeatScope by remember(own) { mutableStateOf(EventScope.ALL) }
            val choiceDay = PlanEvents.repeatChoiceDay(event, occurrenceDay, if (repeats) repeatScope else EventScope.ALL) ?: event.start
            val choices = remember(choiceDay) { PlanEvents.repeatChoices(choiceDay) }
            var choice by remember(own, choiceDay) { mutableStateOf(PlanEvents.choiceIndex(choices, event.repeat)) }
            var until by remember(own) { mutableStateOf(event.until) }
            var busy by remember(own) { mutableStateOf(event.busy != false) }
            var pickingTime by remember { mutableStateOf(false) }
            var pickingDay by remember { mutableStateOf(false) }
            var pickingUntil by remember { mutableStateOf(false) }

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Event title") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            SwitchRow("All day", allDay) { allDay = it }
            if (!allDay) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(onClickLabel = "Change the time") { pickingTime = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Starts at", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        DateDisplay.formatTime(LocalTime.parse(time), format),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text("Length", style = MaterialTheme.typography.bodyLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (if (minutes in EventSheetLengths) EventSheetLengths else (EventSheetLengths + minutes).sorted()).forEach { option ->
                        FilterChip(
                            selected = minutes == option,
                            onClick = { minutes = option },
                            label = { Text(PlannerTimeline.formatLength(option)) },
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(onClickLabel = "Move to another day") { pickingDay = !pickingDay },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Day", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(
                    DateDisplay.formatWeekdayDate(day, clock.date, format),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (pickingDay) {
                MonthCalendar(selected = day, onSelect = {
                    day = it
                    pickingDay = false
                })
            }
            RepeatPicker(
                choices = choices,
                selected = choice,
                onSelect = { choice = it },
                extra = PlanEvents.repeatText(event.repeat).takeIf { choice < 0 },
                scope = repeatScope.takeIf { repeats },
                onScope = { repeatScope = it },
            )
            val repeatsNow = if (choice >= 0) choices[choice].repeat != EventRepeat.None else repeats
            if (repeatsNow) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(onClickLabel = "Choose when it ends") { pickingUntil = !pickingUntil },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Ends on", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        until?.let { DateDisplay.formatWeekdayDate(it, clock.date, format) } ?: "Never",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (pickingUntil) {
                    MonthCalendar(selected = until, onSelect = {
                        until = it
                        pickingUntil = false
                    })
                    if (until != null) {
                        TextButton(onClick = {
                            until = null
                            pickingUntil = false
                        }) { Text("Never ends") }
                    }
                }
            }
            if (!allDay) SwitchRow("Counts as busy time", busy) { busy = it }
            Text(
                text = "Shown on Plan only, on all your devices.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val edit = EventEdit(
                title = title.trim().takeIf { it.isNotEmpty() && it != event.title },
                time = when {
                    allDay && event.time != null -> EditValue(null)
                    !allDay && time != event.time -> EditValue(time)
                    else -> null
                },
                minutes = minutes.takeIf { !allDay && it != event.minutes },
                busy = busy.takeIf { !allDay && it != (event.busy != false) },
                repeat = choices.getOrNull(choice)?.repeat?.takeIf { PlanEvents.encodeRepeat(it) != PlanEvents.encodeRepeat(event.repeat) },
                until = if (until != event.until) EditValue(until) else null,
                day = day.takeIf { it != occurrenceDay },
            )
            val changed = edit != EventEdit()
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (repeats && occurrence.day != null) OutlinedButton(onClick = onSkip) { Text("Skip this one") }
                OutlinedButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                    Button(
                        onClick = { onSave(edit, repeatScope.takeIf { repeats && edit.repeat != null }) },
                        enabled = changed && title.isNotBlank(),
                    ) { Text("Save") }
                }
            }

            if (pickingTime) {
                CustomTimeDialog(
                    initial = LocalTime.parse(time),
                    onPick = {
                        time = TimeBlocks.clockText(it.hour * 60 + it.minute)
                        pickingTime = false
                    },
                    onDismiss = { pickingTime = false },
                )
            }
        }
    }
}

/**
 * The question a change of a repeating Event asks (contract 20.6). A change of the repeat or the end
 * is never "this time only", so [allowThisOnly] leaves it out.
 */
@Composable
internal fun EventScopeDialog(
    title: String,
    allowThisOnly: Boolean,
    onPick: (EventScope) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change $title") },
        text = {
            Column(Modifier.selectableGroup()) {
                if (allowThisOnly) ScopeRow("This time only") { onPick(EventScope.THIS) }
                ScopeRow("This and following") { onPick(EventScope.FOLLOWING) }
                ScopeRow("All times") { onPick(EventScope.ALL) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ScopeRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}
