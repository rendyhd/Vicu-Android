package com.rendyhd.vicu.ui.screens.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.ui.components.shared.LocalDateFormat
import com.rendyhd.vicu.ui.components.shared.VicuDragHandle
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.EventRepeat
import com.rendyhd.vicu.util.IcsImport
import com.rendyhd.vicu.util.IcsImportPreview
import com.rendyhd.vicu.util.PlanEvent
import com.rendyhd.vicu.util.PlanEvents
import com.rendyhd.vicu.util.PlannerTimeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

/**
 * The preview of a calendar file (contract 20.10): how many repeating and single Events it becomes,
 * Busy only, and each Event. Import writes them all in one change; nothing stays linked to the file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImportCalendarSheet(
    text: String,
    importDay: LocalDate,
    zone: TimeZone,
    onImport: (List<PlanEvent>) -> Unit,
    onDismiss: () -> Unit,
) {
    val format = LocalDateFormat.current
    var busyOnly by rememberSaveable { mutableStateOf(false) }
    // A year of a daily series is a few hundred Events: read off the main thread.
    val preview by produceState<IcsImportPreview?>(null, text, busyOnly, importDay, zone) {
        value = null
        value = withContext(Dispatchers.Default) {
            IcsImportPreview(runCatching { IcsImport.importText(text, importDay, zone, busyOnly) }.getOrDefault(emptyList()))
        }
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
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Import calendar file", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Each event becomes one of your events: synced to your other devices and yours to change. " +
                    "Nothing stays linked to the file. A series Vicu cannot repeat the same way is copied as single events for the next 12 months.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val shown = preview
            if (shown == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
            } else {
                Text(IcsImport.summary(shown), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
                if (shown.events.isEmpty()) {
                    Text(
                        "The file has no events from today on.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(value = busyOnly, role = Role.Switch, onValueChange = { busyOnly = it }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Busy only", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Titles become Busy, or Free for a free event",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = busyOnly, onCheckedChange = null)
            }
            if (shown != null && shown.events.isNotEmpty()) {
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    itemsIndexed(shown.events) { _, event ->
                        Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp)) {
                            Text(event.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = importedEventText(event, importDay, format),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                HorizontalDivider()
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    onClick = { shown?.let { onImport(it.events) } },
                    enabled = shown != null && shown.events.isNotEmpty(),
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text("Import")
                }
            }
        }
    }
}

/** "Tue 10 Nov · 09:00 · 1h · Every Tuesday", the way the gear lists your events. */
private fun importedEventText(event: PlanEvent, today: LocalDate, format: com.rendyhd.vicu.util.DateDisplayFormat): String {
    val time = event.time?.let { "${DateDisplay.formatTime(LocalTime.parse(it), format)} · ${PlannerTimeline.formatLength(event.minutes)}" } ?: "All day"
    val parts = mutableListOf(DateDisplay.formatWeekdayDate(event.start, today, format), time)
    if (event.repeat != EventRepeat.None) {
        parts += PlanEvents.repeatText(event.repeat)
        event.until?.let { parts += "until ${DateDisplay.formatWeekdayDate(it, today, format)}" }
    }
    if (event.busy == false) parts += "Free"
    return parts.joinToString(" · ")
}
