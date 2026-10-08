package com.rendyhd.vicu.ui.components.picker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.ui.components.shared.LocalClockDay
import com.rendyhd.vicu.ui.components.shared.LocalDateFormat
import com.rendyhd.vicu.util.DateContext
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.DueDates

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VicuDatePickerDialog(
    currentDate: String?,
    onDateSelected: (String) -> Unit,
    onClearDate: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showFullDatePicker by remember { mutableStateOf(false) }
    // Every date this dialog sets goes through DueDates: date-only, local 23:59:59 of the day.
    val day = LocalClockDay.current
    val dateFormat = LocalDateFormat.current

    if (showFullDatePicker) {
        // The Material picker works in UTC days: hand it the UTC midnight of the stored due date's
        // local calendar day, and read the picked day back the same way.
        val initialMillis = DueDates.datePickerMillis(currentDate, day.zone)
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)

        DatePickerDialog(
            onDismissRequest = { showFullDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val picked = DueDates.dateFromDatePickerMillis(millis)
                            onDateSelected(DueDates.pickDate(picked, day.zone).toString())
                        }
                        showFullDatePicker = false
                        onDismiss()
                    },
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFullDatePicker = false }) {
                    Text("Cancel")
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Due date") },
            text = {
                Column {
                    val hasDate = currentDate != null && !DateUtils.isNullDate(currentDate)
                    if (hasDate) {
                        Text(
                            text = "Current: ${DateDisplay.formatDue(DateContext.CHIP, currentDate, day.date, day.zone, dateFormat)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = {
                                onDateSelected(DueDates.today(day.date, day.zone).toString())
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Today")
                        }

                        FilledTonalButton(
                            onClick = {
                                onDateSelected(DueDates.tomorrow(day.date, day.zone).toString())
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Tomorrow")
                        }

                        FilledTonalButton(
                            onClick = {
                                onDateSelected(DueDates.nextWeek(day.date, day.zone).toString())
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Next Week")
                        }

                        OutlinedButton(
                            onClick = { showFullDatePicker = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Pick a date...")
                        }
                    }

                    if (hasDate) {
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = {
                                onClearDate()
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Clear date", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            },
        )
    }
}
