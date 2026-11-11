package com.rendyhd.vicu.ui.screens.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rendyhd.vicu.calendar.CalendarChoice
import com.rendyhd.vicu.calendar.PlanCalendar
import com.rendyhd.vicu.calendar.PlanCalendars
import com.rendyhd.vicu.calendar.rememberCalendarPermission
import com.rendyhd.vicu.ui.components.shared.LocalDateFormat
import com.rendyhd.vicu.ui.components.shared.VicuDragHandle
import com.rendyhd.vicu.util.CalendarEvents
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.ListedEvent
import com.rendyhd.vicu.util.PlanEvents
import com.rendyhd.vicu.util.PlannerTimeline
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalTime
import org.koin.compose.koinInject

/**
 * Your own Events in the Plan gear, by title (contract 20.3), with New event; and the way to the
 * phone's calendars where this platform reads them.
 */
@Composable
internal fun PlanEventsSection(
    onNewEvent: () -> Unit,
    onOpenEvent: (ListedEvent) -> Unit,
    onOpenCalendars: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val calendars = koinInject<PlanCalendars>()
    val events by calendars.ownEvents.collectAsStateWithLifecycle(emptyList())
    val format = LocalDateFormat.current
    Column(modifier = modifier.fillMaxWidth()) {
        Text("Your events", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Text(
            text = "Times you are not available that are not tasks. They show on Plan only, count as busy and sync to your other devices.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        events.forEach { listed ->
            val event = listed.event
            val time = event.time?.let {
                "${DateDisplay.formatTime(LocalTime.parse(it), format)} · ${PlannerTimeline.formatLength(event.minutes)}"
            } ?: "All day"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(onClickLabel = "Change") { onOpenEvent(listed) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Event,
                    contentDescription = null,
                    tint = calendarColor(PlanEvents.MY_EVENTS_COLOR),
                    modifier = Modifier.padding(end = 12.dp).size(18.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(event.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "$time · ${PlanEvents.repeatText(event.repeat)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        OutlinedButton(onClick = onNewEvent, modifier = Modifier.padding(top = 4.dp)) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("New event", modifier = Modifier.padding(start = 6.dp))
        }
        if (onOpenCalendars != null) {
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(onClickLabel = "Open") { onOpenCalendars() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Calendars", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(
                        "Show the phone's calendars on Plan, read only",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
            }
        }
    }
}

/**
 * The phone's calendars (contract 20.9): the permission is asked when this opens, with why; a
 * refusal leaves Events working. Each calendar, grouped by account: shown, colour, counts as busy,
 * Busy only, kept on this device only.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CalendarsSheet(onDismiss: () -> Unit) {
    val calendars = koinInject<PlanCalendars>()
    val scope = rememberCoroutineScope()
    val permission = rememberCalendarPermission { calendars.accessChanged() }
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        calendars.accessChanged()
        if (!permission.granted && !asked) {
            asked = true
            permission.request()
        }
    }
    val list by calendars.phoneCalendars.collectAsStateWithLifecycle(emptyList())
    var open by rememberSaveable { mutableStateOf<String?>(null) }

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
                .padding(bottom = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Calendars", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Vicu reads the calendars this phone already syncs and shows their events on Plan, where they count as " +
                    "busy time. It never changes them, and nothing about them leaves this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!permission.granted) {
                Text(
                    text = if (permission.refused) {
                        "Calendar access is off. Your own events still work; to show the phone's calendars, allow it in the system settings."
                    } else {
                        "Allow calendar access to choose which calendars Plan shows."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = permission.request) { Text("Allow calendar access") }
                    if (permission.refused) TextButton(onClick = permission.openSettings) { Text("Open settings") }
                }
                return@Column
            }
            if (list.isEmpty()) {
                Text(
                    "This phone has no calendars yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            list.groupBy { it.account }.entries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.key }).forEach { (account, items) ->
                Text(
                    text = account.ifBlank { "On this phone" },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                )
                items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }).forEach { calendar ->
                    CalendarRow(
                        calendar = calendar,
                        expanded = open == calendar.id,
                        onExpand = { open = if (open == calendar.id) null else calendar.id },
                        onChange = { change -> scope.launch { calendars.setChoice(calendar.id, change) } },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarRow(
    calendar: PlanCalendar,
    expanded: Boolean,
    onExpand: () -> Unit,
    onChange: ((CalendarChoice) -> CalendarChoice) -> Unit,
) {
    val choice = calendar.choice
    val color = calendarColor(calendar.color)
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clickable(onClickLabel = if (expanded) "Hide its choices" else "Show its choices") { onExpand() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.padding(end = 12.dp).size(14.dp).clip(CircleShape).background(color))
                Column(Modifier.weight(1f)) {
                    Text(calendar.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = listOfNotNull(
                            "Counts as busy".takeIf { choice.countsAsBusy } ?: "Not busy",
                            "Busy only".takeIf { choice.busyOnly },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(
                checked = choice.shown,
                onCheckedChange = { shown -> onChange { it.copy(shown = shown) } },
                modifier = Modifier.semantics { contentDescription = "Show ${calendar.name} on Plan" },
            )
        }
        if (expanded) {
            Text("Colour", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                val options = listOfNotNull(calendar.ownColor) + CalendarEvents.CALENDAR_COLORS.filter { it != calendar.ownColor }
                options.forEachIndexed { index, hex ->
                    val isOwn = index == 0 && calendar.ownColor != null
                    val selected = if (isOwn) choice.color == null || choice.color == hex else choice.color == hex
                    val swatch = calendarColor(hex)
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .selectable(selected = selected, role = Role.RadioButton) {
                                onChange { it.copy(color = if (isOwn) null else hex) }
                            }
                            .semantics {
                                contentDescription = if (isOwn) "The calendar's own colour" else "Colour ${index + 1}"
                                stateDescription = if (selected) "Chosen" else "Not chosen"
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(swatch)
                                .then(
                                    if (selected) {
                                        Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                    }
                }
            }
            ChoiceSwitch("Counts as busy time", choice.countsAsBusy) { on -> onChange { it.copy(countsAsBusy = on) } }
            ChoiceSwitch("Show only Busy, not titles", choice.busyOnly) { on -> onChange { it.copy(busyOnly = on) } }
            HorizontalDivider(Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun ChoiceSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}
