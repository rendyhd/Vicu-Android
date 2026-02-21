package com.rendyhd.vicu.ui.components.picker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.domain.model.TaskReminder
import com.rendyhd.vicu.util.DateUtils
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private data class RelativeOption(val label: String, val seconds: Long, val relativeTo: String)

private val RELATIVE_OPTIONS = listOf(
    RelativeOption("At due time", 0, "due_date"),
    RelativeOption("5 minutes before", -300, "due_date"),
    RelativeOption("15 minutes before", -900, "due_date"),
    RelativeOption("1 hour before", -3600, "due_date"),
    RelativeOption("1 day before", -86400, "due_date"),
)

/**
 * Format a reminder's absolute time in the user's local timezone.
 */
private fun formatReminderLocal(dateStr: String): String {
    val instant = DateUtils.parseIsoDate(dateStr) ?: return dateStr
    val zoned = instant.atZone(ZoneId.systemDefault())
    val datePart = zoned.toLocalDate().format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
    val timePart = zoned.toLocalTime().format(DateTimeFormatter.ofPattern("h:mm a"))
    return "$datePart $timePart"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderPickerDialog(
    reminders: List<TaskReminder>,
    onAddReminder: (TaskReminder) -> Unit,
    onRemoveReminder: (Int) -> Unit,
    onDismiss: () -> Unit,
    onEditReminder: ((Int, TaskReminder) -> Unit)? = null,
) {
    var showAddOptions by remember { mutableStateOf(false) }
    var showDateTimePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var pickedDateMillis by remember { mutableStateOf<Long?>(null) }
    // -1 = adding new, >= 0 = editing existing at that index
    var editingIndex by remember { mutableIntStateOf(-1) }

    // Pre-fill hour/minute when editing
    var prefillHour by remember { mutableIntStateOf(12) }
    var prefillMinute by remember { mutableIntStateOf(0) }

    if (showDateTimePicker) {
        val datePickerState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = {
                showDateTimePicker = false
                editingIndex = -1
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            pickedDateMillis = millis
                            showDateTimePicker = false
                            showTimePicker = true
                        }
                    },
                ) { Text("Next") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDateTimePicker = false
                    editingIndex = -1
                }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
        return
    }

    if (showTimePicker && pickedDateMillis != null) {
        val timePickerState = rememberTimePickerState(
            initialHour = prefillHour,
            initialMinute = prefillMinute,
        )
        AlertDialog(
            onDismissRequest = {
                showTimePicker = false
                pickedDateMillis = null
                editingIndex = -1
            },
            title = { Text("Set time") },
            text = {
                TimePicker(state = timePickerState)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // DatePicker returns UTC midnight of the selected date
                        val pickedDate = Instant.ofEpochMilli(pickedDateMillis!!)
                            .atZone(ZoneId.of("UTC")).toLocalDate()
                        // Combine with user's picked local time, then convert to UTC for storage
                        val localDateTime = pickedDate.atTime(timePickerState.hour, timePickerState.minute)
                        val zonedLocal = localDateTime.atZone(ZoneId.systemDefault())
                        val iso = DateTimeFormatter.ISO_INSTANT.format(zonedLocal.toInstant())
                        val reminder = TaskReminder(reminder = iso)

                        if (editingIndex >= 0 && onEditReminder != null) {
                            onEditReminder(editingIndex, reminder)
                        } else {
                            onAddReminder(reminder)
                        }
                        showTimePicker = false
                        pickedDateMillis = null
                        showAddOptions = false
                        editingIndex = -1
                    },
                ) { Text(if (editingIndex >= 0) "Save" else "Add") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showTimePicker = false
                    pickedDateMillis = null
                    editingIndex = -1
                }) { Text("Cancel") }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reminders") },
        text = {
            Column {
                if (reminders.isEmpty()) {
                    Text(
                        "No reminders set",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(modifier = Modifier.height((reminders.size * 48).coerceAtMost(200).dp)) {
                        itemsIndexed(reminders) { index, reminder ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        // Tap to edit: only for absolute reminders
                                        if (reminder.reminder.isNotBlank() && !DateUtils.isNullDate(reminder.reminder)) {
                                            editingIndex = index
                                            // Pre-fill with existing time in local timezone
                                            val instant = DateUtils.parseIsoDate(reminder.reminder)
                                            if (instant != null) {
                                                val local = instant.atZone(ZoneId.systemDefault())
                                                prefillHour = local.hour
                                                prefillMinute = local.minute
                                            } else {
                                                prefillHour = 12
                                                prefillMinute = 0
                                            }
                                            showDateTimePicker = true
                                        }
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val text = if (reminder.reminder.isNotBlank() && !DateUtils.isNullDate(reminder.reminder)) {
                                    formatReminderLocal(reminder.reminder)
                                } else if (reminder.relativePeriod != 0L) {
                                    RELATIVE_OPTIONS.find { it.seconds == reminder.relativePeriod }?.label
                                        ?: "${reminder.relativePeriod}s relative"
                                } else {
                                    "At due time"
                                }

                                Text(
                                    text = text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { onRemoveReminder(index) }) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove",
                                        modifier = Modifier.padding(4.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                if (!showAddOptions) {
                    TextButton(
                        onClick = { showAddOptions = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add reminder")
                    }
                } else {
                    Text("Quick add", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        RELATIVE_OPTIONS.forEach { option ->
                            FilledTonalButton(
                                onClick = {
                                    onAddReminder(
                                        TaskReminder(
                                            relativePeriod = option.seconds,
                                            relativeTo = option.relativeTo,
                                        )
                                    )
                                    showAddOptions = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(option.label)
                            }
                        }

                        FilledTonalButton(
                            onClick = {
                                editingIndex = -1
                                // Default to current time + 1 hour
                                val now = ZonedDateTime.now()
                                prefillHour = (now.hour + 1) % 24
                                prefillMinute = 0
                                showDateTimePicker = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Pick date & time...")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
        dismissButton = {},
    )
}
