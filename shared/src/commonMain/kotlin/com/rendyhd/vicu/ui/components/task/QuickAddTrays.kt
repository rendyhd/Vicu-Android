package com.rendyhd.vicu.ui.components.task

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.ui.components.category.LocalProjectCategories
import com.rendyhd.vicu.ui.components.category.PickerCategoryHeading
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.TaskReminder
import com.rendyhd.vicu.location.LocationTrigger
import com.rendyhd.vicu.location.SavedPlace
import com.rendyhd.vicu.ui.components.picker.MonthCalendar
import com.rendyhd.vicu.ui.components.picker.projectPickerRows
import com.rendyhd.vicu.ui.components.shared.LocalDateFormat
import com.rendyhd.vicu.ui.screens.taskdetail.PresetChip
import com.rendyhd.vicu.ui.screens.taskdetail.ReminderPreset
import com.rendyhd.vicu.ui.screens.taskentry.PlacePick
import com.rendyhd.vicu.ui.screens.taskentry.WhenTile
import com.rendyhd.vicu.ui.screens.taskentry.selectedTile
import com.rendyhd.vicu.ui.screens.taskentry.whenTiles
import com.rendyhd.vicu.ui.screens.taskentry.entryPriorityName
import com.rendyhd.vicu.util.PRESET_COLORS
import com.rendyhd.vicu.util.RecurrencePreset
import com.rendyhd.vicu.util.RecurrenceValue
import com.rendyhd.vicu.util.ReminderFormat
import com.rendyhd.vicu.util.TaskTemplate
import com.rendyhd.vicu.util.WhenLogic
import com.rendyhd.vicu.util.WhenValue
import com.rendyhd.vicu.util.detectRecurrencePreset
import com.rendyhd.vicu.util.parseHexArgb
import com.rendyhd.vicu.util.recurrenceForPreset
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

// The trays of the new-task sheet (design concept 3). In the compact sheet a chip opens its tray
// where the keyboard was; in the full-height sheet the same content opens under its row. Every
// pick applies at once: nothing is saved until Add.

/** The chips of the compact sheet that open a tray, and the rows of the full-height sheet. */
internal enum class EntryTray { WHEN, REMIND, PRIORITY, TAGS, PROJECT, REPEAT, TEMPLATE }

/**
 * The account's templates (section 18.3): a tap makes the task, a long press offers Edit and Remove.
 * [details] says what a template holds beyond its title.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TemplateTrayContent(
    templates: List<TaskTemplate>,
    details: (TaskTemplate) -> String?,
    onUse: (TaskTemplate) -> Unit,
    onEdit: (TaskTemplate) -> Unit,
    onRemove: (TaskTemplate) -> Unit,
) {
    Column {
        templates.forEach { template ->
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clip(MaterialTheme.shapes.small)
                        .combinedClickable(
                            onClickLabel = "Make a task from this template",
                            onLongClickLabel = "Edit or remove",
                            onLongClick = { menuOpen = true },
                            onClick = { onUse(template) },
                        )
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        template.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    details(template)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Edit") }, onClick = {
                        menuOpen = false
                        onEdit(template)
                    })
                    DropdownMenuItem(text = { Text("Remove") }, onClick = {
                        menuOpen = false
                        onRemove(template)
                    })
                }
            }
        }
        Text(
            "Hold a template to edit or remove it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )
    }
}

/** A tray's small heading ("Time", "Place"). */
@Composable
private fun TrayHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
}

/**
 * When: the day tiles ([whenTiles]), the times of day, and a month calendar behind "Pick a date".
 * [onPick] gets the new value; null clears the date. [onCustomTime] asks for a time the chips do
 * not offer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WhenTrayContent(
    current: WhenValue,
    today: LocalDate,
    now: LocalTime,
    onPick: (WhenValue?) -> Unit,
    onCustomTime: () -> Unit,
) {
    val fmt = LocalDateFormat.current
    val tiles = whenTiles(current, today, now, fmt)
    val selected = selectedTile(tiles, current, today)
    var calendarOpen by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (pair in tiles.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                for (tile in pair) {
                    WhenTileButton(
                        tile = tile,
                        selected = tile.id == selected,
                        onClick = { onPick(tile.value) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
        TrayHeading("Time")
        val timeIsPreset = current.time == null || current.time in WhenLogic.TIME_CHOICES
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PresetChip(label = "No time", selected = current.date != null && current.time == null, onClick = {
                onPick(WhenLogic.withTime(current, null, today).takeIf { it.date != null })
            })
            for (time in WhenLogic.TIME_CHOICES) {
                PresetChip(
                    label = WhenLogic.timeLabel(time, fmt),
                    selected = current.time == time,
                    onClick = { onPick(WhenLogic.withTime(current, time, today)) },
                )
            }
            PresetChip(
                label = current.time?.takeIf { !timeIsPreset }?.let { WhenLogic.timeLabel(it, fmt) } ?: "Other time…",
                selected = !timeIsPreset,
                onClick = onCustomTime,
            )
        }
        PresetChip(
            label = if (calendarOpen) "Hide calendar" else "Pick a date…",
            icon = Icons.Outlined.CalendarMonth,
            selected = calendarOpen,
            onClick = { calendarOpen = !calendarOpen },
        )
        if (calendarOpen) {
            MonthCalendar(
                selected = current.date,
                onSelect = { picked -> onPick(WhenLogic.withDate(current, picked)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun WhenTileButton(tile: WhenTile, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 52.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = if (tile.hint.isEmpty()) tile.label else "${tile.label}, ${tile.hint}"
                this.selected = selected
            },
        shape = MaterialTheme.shapes.medium,
        color = if (selected) scheme.secondaryContainer else scheme.surfaceContainerHighest,
        contentColor = if (selected) scheme.onSecondaryContainer else scheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                tile.label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (tile.hint.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    tile.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Remind: the time presets ([com.rendyhd.vicu.ui.screens.taskdetail.reminderPresets]) and the
 * saved places to arrive at or leave, each a toggle; "Pick a time" for any other time, which then
 * shows above them with a cross. Without saved places it says where to add them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RemindTrayContent(
    reminders: List<TaskReminder>,
    place: PlacePick?,
    presets: List<ReminderPreset>,
    places: List<SavedPlace>,
    onAdd: (TaskReminder) -> Unit,
    onRemove: (index: Int) -> Unit,
    onPickTime: () -> Unit,
    onPickPlace: (SavedPlace, LocationTrigger) -> Unit,
    onRemovePlace: () -> Unit,
    /** Editing a template: only reminders that count from its due date can be kept (section 18.1). */
    relativeOnly: Boolean = false,
) {
    val fmt = LocalDateFormat.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Reminders the presets below do not show (picked with "Pick a time", or a preset whose
        // time has moved on since): each with a cross.
        val others = reminders.withIndex().filter { (_, reminder) -> presets.none { it.reminder == reminder } }
        if (others.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                others.forEach { (index, reminder) ->
                    val text = ReminderFormat.format(reminder, fmt)
                    PresetChip(
                        label = text,
                        selected = true,
                        icon = Icons.Outlined.Notifications,
                        onClick = onPickTime,
                        onRemove = { onRemove(index) },
                        removeDescription = "Remove reminder $text",
                    )
                }
            }
        }
        if (relativeOnly && presets.isEmpty()) {
            Text(
                "Set a date to add a reminder.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            return@Column
        }
        TrayHeading("Time")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // A preset is a toggle: picked, a second tap takes it off again.
            presets.forEach { preset ->
                val index = reminders.indexOf(preset.reminder)
                PresetChip(
                    label = preset.label,
                    selected = index >= 0,
                    onClick = { if (index >= 0) onRemove(index) else onAdd(preset.reminder) },
                )
            }
            if (!relativeOnly) PresetChip(label = "Pick a time…", icon = Icons.Outlined.Add, onClick = onPickTime)
        }
        if (relativeOnly) return@Column
        TrayHeading("Place")
        if (places.isEmpty()) {
            Text(
                "Save places in Settings, Notifications, Places to be reminded when you arrive or leave.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                places.forEach { saved ->
                    for (trigger in LocationTrigger.entries) {
                        val label = when (trigger) {
                            LocationTrigger.ARRIVE -> "Arrive at ${saved.name}"
                            LocationTrigger.LEAVE -> "Leave ${saved.name}"
                        }
                        val picked = place?.place?.id == saved.id && place.trigger == trigger
                        PresetChip(
                            label = label,
                            icon = Icons.Outlined.Place,
                            selected = picked,
                            onClick = { if (picked) onRemovePlace() else onPickPlace(saved, trigger) },
                        )
                    }
                }
            }
        }
    }
}

/** Priority: None to Urgent, each with its mark. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PriorityTrayContent(current: Int, onPick: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PresetChip(label = "None", selected = current == 0, onClick = { onPick(0) })
        for (level in 1..4) {
            ToggleChip(
                label = entryPriorityName(level).orEmpty(),
                selected = current == level,
                onClick = { onPick(level) },
                leading = { PriorityMark(level) },
            )
        }
    }
}

/**
 * Labels: every label as a chip in its colour, picked ones checked; "New label" names one and picks
 * it. [readFromTitle] are the names the title's "*tag" words add; they show as picked.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TagsTrayContent(
    labels: List<Label>,
    selectedIds: Set<Long>,
    readFromTitle: List<String>,
    onToggle: (Long) -> Unit,
    onCreate: (name: String, hexColor: String) -> Unit,
) {
    var naming by remember { mutableStateOf(false) }
    val fromTitle = readFromTitle.map { it.lowercase() }.toSet()
    // The dot is the label's chip text colour: the label colour moved until it reads in this theme.
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.sortedBy { it.title.lowercase() }.forEach { label ->
            val picked = label.id in selectedIds || label.title.lowercase() in fromTitle
            ToggleChip(
                label = label.title,
                selected = picked,
                onClick = { onToggle(label.id) },
                leading = {
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(
                                parseHexArgb(label.hexColor)?.let { labelChipColors(it, dark).text }
                                    ?: MaterialTheme.colorScheme.outline,
                                CircleShape,
                            ),
                    )
                },
            )
        }
        PresetChip(label = "New label", icon = Icons.Outlined.Add, onClick = { naming = true })
    }
    if (naming) {
        NewTagDialog(
            onCreate = { name ->
                naming = false
                onCreate(name, PRESET_COLORS[labels.size % PRESET_COLORS.size])
            },
            onDismiss = { naming = false },
        )
    }
}

@Composable
private fun NewTagDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New label") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name.trim()) }, enabled = name.isNotBlank()) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Project: the project tree with the Inbox first, one radio each. */
@Composable
internal fun ProjectTrayContent(projects: List<Project>, inboxProjectId: Long, selectedId: Long, onPick: (Long) -> Unit) {
    val categories = LocalProjectCategories.current
    val rows = remember(projects, inboxProjectId, categories) { projectPickerRows(projects, inboxProjectId, "", categories) }
    Column {
        rows.forEach { row ->
            val selected = row.project.id == selectedId
            row.categoryHeader?.let { PickerCategoryHeading(it, Modifier.padding(start = 12.dp)) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onPick(row.project.id) })
                    .padding(start = (row.depth * 20).dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
                Text(
                    row.project.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Repeat: none, daily, weekly, monthly, yearly; "Other…" opens the full repeat picker. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RepeatTrayContent(current: RecurrenceValue, onPick: (RecurrenceValue) -> Unit, onOther: () -> Unit) {
    val preset = detectRecurrencePreset(current)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            RecurrencePreset.NONE to "None",
            RecurrencePreset.DAILY to "Daily",
            RecurrencePreset.WEEKLY to "Weekly",
            RecurrencePreset.MONTHLY to "Monthly",
            RecurrencePreset.YEARLY to "Yearly",
        ).forEach { (option, label) ->
            PresetChip(label = label, selected = preset == option, onClick = { onPick(recurrenceForPreset(option)) })
        }
        PresetChip(label = "Other…", selected = preset == RecurrencePreset.CUSTOM, onClick = onOther)
    }
}

/** A chip with something before its text (a colour dot, a priority mark) and a check once picked. */
@Composable
private fun ToggleChip(label: String, selected: Boolean, onClick: () -> Unit, leading: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides MIN_TOUCH_TARGET) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = if (selected) scheme.secondaryContainer else scheme.surfaceContainerHighest,
            contentColor = if (selected) scheme.onSecondaryContainer else scheme.onSurface,
            modifier = Modifier.minimumInteractiveComponentSize().semantics { this.selected = selected },
        ) {
            Row(
                modifier = Modifier.heightIn(min = 36.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (selected) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                } else {
                    Box(Modifier.width(16.dp), contentAlignment = Alignment.Center) { leading() }
                }
                Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
