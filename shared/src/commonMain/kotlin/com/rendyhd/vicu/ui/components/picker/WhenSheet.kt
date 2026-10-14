package com.rendyhd.vicu.ui.components.picker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.ui.components.shared.LocalClockDay
import com.rendyhd.vicu.ui.components.shared.LocalDateFormat
import com.rendyhd.vicu.ui.components.shared.LocalIs24Hour
import com.rendyhd.vicu.ui.components.shared.VicuDragHandle
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.util.SystemTimeSource
import com.rendyhd.vicu.util.WhenLogic
import com.rendyhd.vicu.util.WhenQuickChoice
import com.rendyhd.vicu.util.WhenValue
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

/**
 * The When sheet (design review card 3.4b), the Android counterpart of the desktop When panel:
 * a text field read by the quick-add parser, the quick choices, the Material calendar and a row of
 * times. A quick choice or Done finishes; the calendar and the time chips only change the value,
 * and the text follows them. The rules live in [WhenLogic]. A day without a time becomes a
 * date-only due date (local 23:59:59, see [DueDates]).
 *
 * [onDateSelected] gets the due date as the ISO instant the task stores; [onClearDate] is offered
 * only when [currentDate] holds a date.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WhenSheet(
    currentDate: String?,
    onDateSelected: (String) -> Unit,
    onClearDate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clockDay = LocalClockDay.current
    val zone = clockDay.zone
    val today = clockDay.date
    val fmt = LocalDateFormat.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val initial = remember { WhenLogic.valueOfDue(currentDate, zone) }
    var selected by remember { mutableStateOf(initial) }
    // What the text reads as while the user types: shown in the calendar and the time chips, and
    // taken over by Done (or the keyboard's Done).
    var typed by remember { mutableStateOf<WhenValue?>(null) }
    var text by remember { mutableStateOf(WhenLogic.text(initial, today, fmt)) }
    var showCustomTime by remember { mutableStateOf(false) }
    val shown = typed ?: selected

    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = initial.date?.let(::utcMillis),
        initialDisplayedMonthMillis = utcMillis(initial.date ?: today),
    )

    fun close(after: () -> Unit = {}) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            after()
            onDismiss()
        }
    }

    fun finish(value: WhenValue) {
        val due = WhenLogic.dueOf(value, zone)
        close { if (due != null) onDateSelected(due.toString()) }
    }

    /** A value picked by hand: the text follows it. */
    fun apply(value: WhenValue) {
        typed = null
        selected = value
        text = WhenLogic.text(value, today, fmt)
    }

    // The calendar shows the value: a quick choice or typed text moves its selection and month.
    LaunchedEffect(shown.date) { datePickerState.show(shown.date) }
    // A tap on a day of the calendar picks it (the time is kept).
    val currentShown by rememberUpdatedState(shown)
    LaunchedEffect(datePickerState) {
        snapshotFlow { datePickerState.selectedDateMillis }.collect { millis ->
            val picked = millis?.let { DueDates.dateFromDatePickerMillis(it) } ?: return@collect
            if (picked != currentShown.date) apply(WhenLogic.withDate(currentShown, picked))
        }
    }

    val typedReadsNothing = typed == null && text.isNotBlank() && text != WhenLogic.text(selected, today, fmt)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { VicuDragHandle() },
    ) {
        Column(modifier = Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("When", style = MaterialTheme.typography.titleMedium)

                OutlinedTextField(
                    value = text,
                    onValueChange = { raw ->
                        text = raw
                        val now = SystemTimeSource.now().toLocalDateTime(zone)
                        typed = WhenLogic.parseText(raw, now, zone)?.value
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Date and time") },
                    placeholder = { Text("Type a day, a date or a time") },
                    singleLine = true,
                    isError = typedReadsNothing,
                    supportingText = {
                        val value = typed
                        when {
                            value != null && text != WhenLogic.text(value, today, fmt) ->
                                Text(WhenLogic.text(value, today, fmt))
                            typedReadsNothing -> Text("No date found")
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { typed?.let(::finish) }),
                )

                val choices = remember(today) { WhenLogic.quickChoices(today) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (pair in choices.chunked(2)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            for (choice in pair) {
                                QuickChoiceButton(
                                    choice = choice,
                                    hint = WhenLogic.hint(choice, fmt),
                                    onClick = { finish(WhenLogic.withDate(shown, choice.date)) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }

                DatePicker(
                    state = datePickerState,
                    modifier = Modifier.fillMaxWidth(),
                    title = null,
                    headline = null,
                    showModeToggle = false,
                )

                Text("Time", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val timeIsPreset = shown.time != null && shown.time in WhenLogic.TIME_CHOICES
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    FilterChip(
                        selected = shown.time == null,
                        onClick = { apply(WhenLogic.withTime(shown, null, today)) },
                        label = { Text("None") },
                    )
                    for (time in WhenLogic.TIME_CHOICES) {
                        FilterChip(
                            selected = shown.time == time,
                            onClick = { apply(WhenLogic.withTime(shown, time, today)) },
                            label = { Text(WhenLogic.timeLabel(time, fmt)) },
                        )
                    }
                    FilterChip(
                        selected = shown.time != null && !timeIsPreset,
                        onClick = { showCustomTime = true },
                        label = {
                            Text(shown.time?.takeIf { !timeIsPreset }?.let { WhenLogic.timeLabel(it, fmt) } ?: "Custom")
                        },
                    )
                }
            }

            // Pinned under the scrolling content so Done is always in reach.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (initial.date != null) {
                    TextButton(onClick = { close(onClearDate) }) {
                        Text("Clear date", color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { if (shown.date != null) finish(shown) else close() }) {
                    Text("Done")
                }
            }
        }
    }

    if (showCustomTime) {
        CustomTimeDialog(
            initial = shown.time ?: LocalTime(9, 0),
            onPick = { time ->
                showCustomTime = false
                apply(WhenLogic.withTime(shown, time, today))
            },
            onDismiss = { showCustomTime = false },
        )
    }
}

@Composable
private fun QuickChoiceButton(
    choice: WhenQuickChoice,
    hint: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) { contentDescription = "${choice.label}, $hint" },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(choice.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomTimeDialog(initial: LocalTime, onPick: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = LocalIs24Hour.current,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set time") },
        text = { TimePicker(state = state) },
        confirmButton = { TextButton(onClick = { onPick(LocalTime(state.hour, state.minute)) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The Material date picker works in UTC days: the UTC midnight of a local calendar day. */
private fun utcMillis(date: LocalDate): Long = date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

/** Moves the picker's selection (and month) to [date] when it is not there yet. */
@OptIn(ExperimentalMaterial3Api::class)
private fun DatePickerState.show(date: LocalDate?) {
    val millis = date?.let(::utcMillis)
    if (selectedDateMillis == millis) return
    selectedDateMillis = millis
    if (millis != null) displayedMonthMillis = millis
}
