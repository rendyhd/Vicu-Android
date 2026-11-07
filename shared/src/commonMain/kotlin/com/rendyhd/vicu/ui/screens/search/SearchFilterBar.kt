package com.rendyhd.vicu.ui.screens.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.ui.components.picker.MonthCalendar
import com.rendyhd.vicu.ui.components.shared.LocalClockDay
import com.rendyhd.vicu.ui.components.shared.LocalDateFormat
import com.rendyhd.vicu.ui.components.task.LabelChip
import com.rendyhd.vicu.ui.components.task.PriorityMark
import com.rendyhd.vicu.ui.screens.taskdetail.PresetChip
import com.rendyhd.vicu.ui.screens.taskdetail.priorityName
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.SearchFilters
import kotlinx.datetime.LocalDate

private enum class OpenFilter { DATE, STATUS, LABELS, PRIORITY }

private val DATE_CHOICES = listOf(
    SearchFilters.DATE_ALL to "Any",
    "overdue" to "Overdue",
    "today" to "Today",
    "this_week" to "This week",
    "this_month" to "This month",
    "no_due_date" to "No date",
)

private val STATUS_CHOICES = listOf(
    SearchFilters.STATUS_ALL to "All",
    SearchFilters.STATUS_OPEN to "Open",
    SearchFilters.STATUS_DONE to "Completed",
)

private val PRIORITY_CHOICES = listOf(1, 2, 3, 4, 5)

/**
 * The four filters under the search field (contract section 17): a chip each, showing its value and
 * an X once set. A row that does not fit scrolls sideways. Each opens a small sheet.
 */
@Composable
internal fun SearchFilterBar(
    filters: SearchFilters,
    labels: List<Label>,
    onChange: (SearchFilters) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf<OpenFilter?>(null) }
    val focusManager = LocalFocusManager.current
    val today = LocalClockDay.current.date
    val format = LocalDateFormat.current
    fun show(filter: OpenFilter) {
        focusManager.clearFocus()
        open = filter
    }

    fun day(date: LocalDate) = DateDisplay.formatWeekdayDate(date, today, format)
    val dateText = when (filters.date) {
        SearchFilters.DATE_ALL -> null
        SearchFilters.DATE_RANGE -> {
            val from = filters.from
            val to = filters.to
            when {
                from == null || to == null -> "Pick dates"
                from == to -> day(from)
                from < to -> "${day(from)} to ${day(to)}"
                else -> "${day(to)} to ${day(from)}"
            }
        }
        else -> DATE_CHOICES.firstOrNull { it.first == filters.date }?.second
    }
    val statusText = STATUS_CHOICES.firstOrNull { it.first == filters.status && it.first != SearchFilters.STATUS_ALL }?.second
    val labelsText = when (filters.labelIds.size) {
        0 -> null
        1 -> labels.firstOrNull { it.id in filters.labelIds }?.title ?: "1 label"
        else -> "${filters.labelIds.size} labels"
    }
    val priorityText = when (filters.priorities.size) {
        0 -> null
        1 -> priorityName(filters.priorities.first())
        else -> "${filters.priorities.size} priorities"
    }

    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip("Date", dateText, { show(OpenFilter.DATE) }) { onChange(filters.copy(date = SearchFilters.DATE_ALL, from = null, to = null)) }
        FilterChip("Status", statusText, { show(OpenFilter.STATUS) }) { onChange(filters.copy(status = SearchFilters.STATUS_ALL)) }
        FilterChip("Labels", labelsText, { show(OpenFilter.LABELS) }) { onChange(filters.copy(labelIds = emptySet())) }
        FilterChip("Priority", priorityText, { show(OpenFilter.PRIORITY) }) { onChange(filters.copy(priorities = emptySet())) }
    }

    when (open) {
        OpenFilter.DATE -> DateSheet(filters, onChange) { open = null }
        OpenFilter.STATUS -> FilterSheet("Status", { open = null }) {
            STATUS_CHOICES.forEach { (value, label) ->
                ChoiceRow(label, selected = filters.status == value) {
                    onChange(filters.copy(status = value))
                    open = null
                }
            }
        }
        OpenFilter.LABELS -> FilterSheet("Labels", { open = null }) {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(labels, key = { it.id }) { label ->
                    val ticked = label.id in filters.labelIds
                    TickRow(ticked, { onChange(filters.copy(labelIds = if (ticked) filters.labelIds - label.id else filters.labelIds + label.id)) }) {
                        LabelChip(title = label.title, hexColor = label.hexColor)
                    }
                }
            }
        }
        OpenFilter.PRIORITY -> FilterSheet("Priority", { open = null }) {
            PRIORITY_CHOICES.forEach { level ->
                val ticked = level in filters.priorities
                TickRow(ticked, { onChange(filters.copy(priorities = if (ticked) filters.priorities - level else filters.priorities + level)) }) {
                    PriorityMark(level)
                    Spacer(Modifier.size(8.dp))
                    Text(priorityName(level).orEmpty(), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        null -> Unit
    }
}

@Composable
private fun FilterChip(name: String, value: String?, onClick: () -> Unit, onClear: () -> Unit) {
    if (value == null) {
        PresetChip(label = name, onClick = onClick)
    } else {
        PresetChip(
            label = value,
            onClick = onClick,
            selected = true,
            onRemove = onClear,
            removeDescription = "Clear $name filter",
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            content()
        }
    }
}

/** Date's list, and "Pick dates": the first day tapped starts the range and the second ends it. */
@Composable
private fun DateSheet(filters: SearchFilters, onChange: (SearchFilters) -> Unit, onDismiss: () -> Unit) {
    var picking by remember { mutableStateOf(false) }
    var first by remember { mutableStateOf<LocalDate?>(null) }
    if (picking) {
        FilterSheet(if (first == null) "First day" else "Last day", onDismiss) {
            MonthCalendar(
                selected = first ?: filters.from,
                onSelect = { day ->
                    val start = first
                    if (start == null) {
                        first = day
                    } else {
                        onChange(filters.copy(date = SearchFilters.DATE_RANGE, from = start, to = day))
                        onDismiss()
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        return
    }
    FilterSheet("Date", onDismiss) {
        DATE_CHOICES.forEach { (value, label) ->
            ChoiceRow(label, selected = filters.date == value) {
                onChange(filters.copy(date = value, from = null, to = null))
                onDismiss()
            }
        }
        ChoiceRow("Pick dates", selected = filters.date == SearchFilters.DATE_RANGE) {
            first = null
            picking = true
        }
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Outlined.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun TickRow(ticked: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Checkbox, onClick = onToggle)
            .padding(start = 12.dp, end = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = ticked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
        content()
    }
}
