package com.rendyhd.vicu.ui.components.picker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.domain.model.TaskReminder
import com.rendyhd.vicu.ui.components.shared.LocalDateFormat
import com.rendyhd.vicu.ui.components.shared.LocalIs24Hour
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.util.ReminderFormat
import com.rendyhd.vicu.util.ReminderPickerStart
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

private data class RelativeOption(val label: String, val seconds: Long, val relativeTo: String)

private val RELATIVE_OPTIONS = listOf(
    RelativeOption("At due time", 0, "due_date"),
    RelativeOption("5 minutes before", -300, "due_date"),
    RelativeOption("15 minutes before", -900, "due_date"),
    RelativeOption("1 hour before", -3600, "due_date"),
    RelativeOption("1 day before", -86400, "due_date"),
)

// Where the add or edit of an absolute reminder is. The list dialog stays underneath the pickers.
private const val STEP_LIST = 0
private const val STEP_DATE = 1
private const val STEP_TIME = 2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderPickerDialog(
    reminders: List<TaskReminder>,
    onAddReminder: (TaskReminder) -> Unit,
    onRemoveReminder: (Int) -> Unit,
    onDismiss: () -> Unit,
    onEditReminder: ((Int, TaskReminder) -> Unit)? = null,
    dueDate: String = "",
) {
    // Relative reminders ("15 min before" etc.) anchor to the due date; without one they'd
    // never fire, so disable them and tell the user why.
    val hasDueDate = dueDate.isNotBlank() && !DateUtils.isNullDate(dueDate)
    val is24Hour = LocalIs24Hour.current
    val dateFormat = LocalDateFormat.current
    var showAddOptions by rememberSaveable { mutableStateOf(false) }
    var step by rememberSaveable { mutableStateOf(STEP_LIST) }
    var pickedDateMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    // -1 = adding new, >= 0 = editing existing at that index
    var editingIndex by rememberSaveable { mutableStateOf(-1) }

    // Where the date and time pickers open: on the reminder being edited, else on the next hour.
    var startDateMillis by rememberSaveable { mutableStateOf(0L) }
    var startHour by rememberSaveable { mutableStateOf(12) }
    var startMinute by rememberSaveable { mutableStateOf(0) }

    fun openPickers(existing: String?, index: Int) {
        val start = ReminderPickerStart.of(existing, Clock.System.now(), TimeZone.currentSystemDefault())
        startDateMillis = start.dateMillis
        startHour = start.hour
        startMinute = start.minute
        editingIndex = index
        pickedDateMillis = null
        step = STEP_DATE
    }

    fun closePickers() {
        step = STEP_LIST
        pickedDateMillis = null
        editingIndex = -1
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
                    LazyColumn(modifier = Modifier.weight(1f, fill = false).heightIn(max = 224.dp)) {
                        itemsIndexed(reminders, contentType = { _, _ -> "reminder" }) { index, reminder ->
                            // Tap to edit: only for absolute reminders
                            val editable = reminder.reminder.isNotBlank() && !DateUtils.isNullDate(reminder.reminder)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = editable) { openPickers(reminder.reminder, index) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = ReminderFormat.format(reminder, dateFormat),
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
                        if (!hasDueDate) {
                            Text(
                                "Set a due date to use relative reminders",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
                                enabled = hasDueDate,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(option.label)
                            }
                        }

                        FilledTonalButton(
                            onClick = { openPickers(existing = null, index = -1) },
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

    if (step == STEP_DATE) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = startDateMillis)
        DatePickerDialog(
            onDismissRequest = ::closePickers,
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            pickedDateMillis = millis
                            step = STEP_TIME
                        }
                    },
                ) { Text("Next") }
            },
            dismissButton = {
                TextButton(onClick = ::closePickers) { Text("Cancel") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    val pickedMillis = pickedDateMillis
    if (step == STEP_TIME && pickedMillis != null) {
        val timePickerState = rememberTimePickerState(
            initialHour = startHour,
            initialMinute = startMinute,
            is24Hour = is24Hour,
        )
        AlertDialog(
            onDismissRequest = ::closePickers,
            title = { Text("Set time") },
            text = {
                TimePicker(state = timePickerState)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // DatePicker returns UTC midnight of the selected date
                        val pickedDate = DueDates.dateFromDatePickerMillis(pickedMillis)
                        // Combine with user's picked local time, then convert to UTC for storage
                        val localDateTime = LocalDateTime(
                            pickedDate.year, pickedDate.monthNumber, pickedDate.dayOfMonth,
                            timePickerState.hour, timePickerState.minute
                        )
                        val iso = localDateTime.toInstant(TimeZone.currentSystemDefault()).toString()
                        val reminder = TaskReminder(reminder = iso)

                        if (editingIndex >= 0 && onEditReminder != null) {
                            onEditReminder(editingIndex, reminder)
                        } else {
                            onAddReminder(reminder)
                        }
                        showAddOptions = false
                        closePickers()
                    },
                ) { Text(if (editingIndex >= 0) "Save" else "Add") }
            },
            dismissButton = {
                TextButton(onClick = ::closePickers) { Text("Cancel") }
            },
        )
    }
}
