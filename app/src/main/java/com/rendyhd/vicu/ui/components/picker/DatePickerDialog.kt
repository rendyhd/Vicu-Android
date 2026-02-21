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
import com.rendyhd.vicu.util.DateUtils
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VicuDatePickerDialog(
    currentDate: String?,
    onDateSelected: (String) -> Unit,
    onClearDate: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showFullDatePicker by remember { mutableStateOf(false) }

    if (showFullDatePicker) {
        val initialMillis = DateUtils.parseIsoDate(currentDate)?.toEpochMilli()
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)

        DatePickerDialog(
            onDismissRequest = { showFullDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val instant = Instant.ofEpochMilli(millis)
                            val iso = DateTimeFormatter.ISO_INSTANT.format(
                                instant.atZone(ZoneOffset.UTC).toLocalDate()
                                    .atTime(12, 0).toInstant(ZoneOffset.UTC)
                            )
                            onDateSelected(iso)
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
            title = { Text("Set due date") },
            text = {
                Column {
                    val hasDate = currentDate != null && !DateUtils.isNullDate(currentDate)
                    if (hasDate) {
                        Text(
                            text = "Current: ${DateUtils.formatFullDate(currentDate)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = {
                                onDateSelected(DateUtils.todayEndIso())
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Today")
                        }

                        FilledTonalButton(
                            onClick = {
                                onDateSelected(DateUtils.tomorrowIso())
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Tomorrow")
                        }

                        FilledTonalButton(
                            onClick = {
                                onDateSelected(DateUtils.nextWeekIso())
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
