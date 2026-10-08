package com.rendyhd.vicu.ui.screens.routines

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FabPosition
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.rendyhd.vicu.domain.model.HealthSubtype
import com.rendyhd.vicu.domain.model.OccurrenceStatus
import com.rendyhd.vicu.domain.model.Routine
import com.rendyhd.vicu.domain.model.RoutineDay
import com.rendyhd.vicu.domain.model.RoutineDraft
import com.rendyhd.vicu.domain.model.RoutineKind
import com.rendyhd.vicu.domain.model.RoutineOccurrence
import com.rendyhd.vicu.domain.model.RoutineOccurrenceRecord
import com.rendyhd.vicu.domain.model.RoutinePeriod
import com.rendyhd.vicu.domain.model.RoutineSchedule
import com.rendyhd.vicu.domain.model.RoutineSlot
import com.rendyhd.vicu.ui.components.shared.EmptyState
import com.rendyhd.vicu.ui.components.shared.LocalFabAlignStart
import com.rendyhd.vicu.ui.components.shared.copyPlainText
import com.rendyhd.vicu.ui.components.shared.LocalToday
import com.rendyhd.vicu.ui.components.shared.VicuFab
import com.rendyhd.vicu.ui.components.shared.VicuTopAppBar
import org.koin.compose.viewmodel.koinViewModel
import com.rendyhd.vicu.ui.components.shared.LocalDateFormat
import com.rendyhd.vicu.util.DateContext
import com.rendyhd.vicu.util.DateDisplay
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

private val HealthColor = Color(0xFF2E9D78)
private val ChoreColor = Color(0xFF5576D1)

@Composable
fun RoutinesScreen(
    onOpenDrawer: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    viewModel: RoutinesViewModel = koinViewModel(),
) {
    val routinesEnabled by viewModel.routinesEnabled.collectAsStateWithLifecycle()
    if (!routinesEnabled) {
        RoutinesTurnedOff(onOpenDrawer, onNavigateToSearch)
        return
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val remindersEnabled by viewModel.remindersEnabled.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var pageMenuOpen by remember { mutableStateOf(false) }
    var editorRoutine by remember { mutableStateOf<Routine?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Routine?>(null) }
    var historyRoutine by remember { mutableStateOf<Routine?>(null) }

    Scaffold(
        topBar = {
            VicuTopAppBar(
                title = { Text("Routines") },
                onOpenDrawer = onOpenDrawer,
                onNavigateToSearch = onNavigateToSearch,
                extraActions = {
                    Box {
                        IconButton(onClick = { pageMenuOpen = true }) {
                            Icon(Icons.Outlined.MoreVert, "Routine options")
                        }
                        DropdownMenu(expanded = pageMenuOpen, onDismissRequest = { pageMenuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(if (remindersEnabled) "Pause all reminders" else "Resume reminders") },
                                trailingIcon = {
                                    Switch(
                                        checked = remindersEnabled,
                                        onCheckedChange = null,
                                    )
                                },
                                onClick = {
                                    pageMenuOpen = false
                                    viewModel.setRemindersEnabled(!remindersEnabled)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Copy history as CSV") },
                                onClick = {
                                    pageMenuOpen = false
                                    viewModel.exportCsv { export ->
                                        scope.launch {
                                            clipboard.copyPlainText("Routine history", export.csv)
                                            snackbar.showSnackbar(
                                                if (export.complete) {
                                                    "Routine history copied"
                                                } else {
                                                    "Routine history copied, without older history that could not be loaded"
                                                },
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            VicuFab(
                onClick = {
                    editorRoutine = null
                    showEditor = true
                },
                label = "New routine",
            )
        },
        floatingActionButtonPosition = if (LocalFabAlignStart.current) FabPosition.Start else FabPosition.End,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "summary", contentType = "summary") {
                RoutineDaySummary(
                    day = state.day,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (state.day.occurrences.isEmpty()) {
                item(key = "empty_today", contentType = "empty") {
                    Text(
                        text = if (state.active.isEmpty()) {
                            "Add a supplement, medication, or household rhythm."
                        } else {
                            "Nothing scheduled for today."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                }
            } else {
                items(state.day.occurrences, key = { it.key }, contentType = { "occurrence" }) { occurrence ->
                    RoutineOccurrenceRow(
                        occurrence = occurrence,
                        onToggle = { viewModel.toggle(occurrence) },
                        onSkip = { viewModel.skip(occurrence) },
                        modifier = Modifier.padding(horizontal = 12.dp).animateItem(),
                    )
                }
            }

            item(key = "all_header", contentType = "header") { SectionLabel("ALL ROUTINES") }
            items(state.active, key = { it.definition.id }, contentType = { "routine" }) { routine ->
                RoutineDefinitionCard(
                    routine = routine,
                    onEdit = {
                        editorRoutine = routine
                        showEditor = true
                    },
                    onArchive = { viewModel.archive(routine, true) },
                    onDelete = { pendingDelete = routine },
                    onOpenHistory = { historyRoutine = routine },
                    modifier = Modifier.padding(horizontal = 12.dp).animateItem(),
                )
            }

            if (state.archived.isNotEmpty()) {
                item(key = "archived_header", contentType = "header") { SectionLabel("ARCHIVED") }
                items(state.archived, key = { "archived_${it.definition.id}" }, contentType = { "routine" }) { routine ->
                    RoutineDefinitionCard(
                        routine = routine,
                        onEdit = {},
                        onArchive = { viewModel.archive(routine, false) },
                        onDelete = { pendingDelete = routine },
                        onOpenHistory = { historyRoutine = routine },
                        archived = true,
                        modifier = Modifier.padding(horizontal = 12.dp).animateItem(),
                    )
                }
            }

            if (state.issues.isNotEmpty()) {
                item(key = "issues", contentType = "notice") {
                    Text(
                        text = "${state.issues.size} routine ${if (state.issues.size == 1) "record needs" else "records need"} repair",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            state.archiveWarning?.let { warning ->
                item(key = "archive-warning", contentType = "notice") {
                    Text(
                        text = warning,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            item { Spacer(Modifier.height(88.dp)) }
        }
    }

    if (showEditor) {
        RoutineEditorDialog(
            routine = editorRoutine,
            saving = state.isSaving,
            onDismiss = { if (!state.isSaving) showEditor = false },
            onSave = { draft ->
                viewModel.save(draft, editorRoutine?.definition?.id) {
                    showEditor = false
                }
            },
        )
    }

    pendingDelete?.let { routine ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${routine.definition.name}?") },
            text = { Text("Its synced and local history will be permanently removed.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePermanently(routine)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }

    historyRoutine?.let { routine ->
        // One flow per routine, not one per recomposition: a new instance would restart the
        // collection and flash the dialog empty.
        val historyFlow = remember(routine.definition.id) { viewModel.observeHistory(routine.definition.id) }
        val history by historyFlow.collectAsStateWithLifecycle(emptyList())
        RoutineHistoryDialog(
            routine = routine,
            history = history,
            onDismiss = { historyRoutine = null },
        )
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }
}

@Composable
fun RoutineDaySummary(day: RoutineDay, modifier: Modifier = Modifier) {
    val progress = if (day.scheduledCount == 0) "Ready when you are" else "${day.completedCount} of ${day.scheduledCount} done"
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
            CircularProgressIndicator(
                progress = { if (day.scheduledCount == 0) 0f else day.completedCount.toFloat() / day.scheduledCount },
                modifier = Modifier.fillMaxSize(),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            Text(day.completedCount.toString(), style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text("Today’s rhythm", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(progress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun RoutineOccurrenceRow(
    occurrence: RoutineOccurrence,
    onToggle: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val complete = occurrence.status == OccurrenceStatus.COMPLETED
    val skipped = occurrence.status == OccurrenceStatus.SKIPPED
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onToggle),
        colors = CardDefaults.cardColors(
            containerColor = if (complete) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f)
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onToggle) {
                Icon(
                    if (complete) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = if (complete) "Undo" else "Complete",
                    tint = if (complete) MaterialTheme.colorScheme.primary else kindColor(occurrence.routine.definition.kind),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    occurrence.routine.definition.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val detail = buildList {
                    if (occurrence.routine.definition.amount.isNotBlank()) {
                        add(listOf(occurrence.routine.definition.amount, occurrence.routine.definition.unit).filter { it.isNotBlank() }.joinToString(" "))
                    }
                    add(occurrence.slot.label.ifBlank { periodLabel(occurrence.slot.period) })
                    if (occurrence.overdue) add("overdue")
                    if (skipped) add("skipped")
                }.joinToString(" · ")
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!complete && !skipped) {
                TextButton(onClick = onSkip) { Text("Skip") }
            }
        }
    }
}

@Composable
private fun RoutineDefinitionCard(
    routine: Routine,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onOpenHistory: () -> Unit,
    archived: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onOpenHistory),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (routine.definition.kind == RoutineKind.HEALTH) Icons.Outlined.FavoriteBorder else Icons.Outlined.Home,
                contentDescription = null,
                tint = kindColor(routine.definition.kind),
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(routine.definition.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    routineSummary(routine),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, "Routine actions") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (!archived) {
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                            onClick = { menuOpen = false; onEdit() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(if (archived) "Restore" else "Archive") },
                        leadingIcon = { Icon(if (archived) Icons.Outlined.Restore else Icons.Outlined.Archive, null) },
                        onClick = { menuOpen = false; onArchive() },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun RoutineHistoryDialog(
    routine: Routine,
    history: List<RoutineOccurrenceRecord>,
    onDismiss: () -> Unit,
) {
    val logged = history.filter { it.status != OccurrenceStatus.PENDING }
    val completed = logged.count { it.status == OccurrenceStatus.COMPLETED }
    val adherence = if (logged.isEmpty()) 0 else (completed * 100 / logged.size)
    val historyToday = LocalToday.current
    val dateFormat = LocalDateFormat.current
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp),
            shape = RoundedCornerShape(28.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(routine.definition.name, style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (logged.isEmpty()) "No history yet" else "$adherence% completed · $completed of ${logged.size} logged",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                if (logged.isEmpty()) {
                    Text("Daily activity will appear here as you complete or skip it.")
                } else {
                    LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                        items(logged.take(30), key = { it.key }, contentType = { "record" }) { record ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    if (record.status == OccurrenceStatus.COMPLETED) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                                    contentDescription = null,
                                    tint = if (record.status == OccurrenceStatus.COMPLETED) HealthColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        runCatching { DateDisplay.formatDay(DateContext.CHIP, LocalDate.parse(record.scheduledDate), historyToday, dateFormat) }
                                            .getOrDefault(record.scheduledDate),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        record.status.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(minutesText(record.scheduledMinutes), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Done") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoutineEditorDialog(
    routine: Routine?,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (RoutineDraft) -> Unit,
) {
    val definition = routine?.definition
    var name by remember(routine) { mutableStateOf(definition?.name.orEmpty()) }
    var kind by remember(routine) { mutableStateOf(definition?.kind ?: RoutineKind.HEALTH) }
    var subtype by remember(routine) { mutableStateOf(definition?.healthSubtype ?: HealthSubtype.SUPPLEMENT) }
    var amount by remember(routine) { mutableStateOf(definition?.amount.orEmpty()) }
    var unit by remember(routine) { mutableStateOf(definition?.unit.orEmpty()) }
    val initialPeriods = definition?.slots?.map { it.period }?.toSet().orEmpty()
    var periods by remember(routine) { mutableStateOf(initialPeriods.ifEmpty { setOf(RoutinePeriod.MORNING) }) }
    var reminders by remember(routine) { mutableStateOf(definition?.slots?.any { it.reminderEnabled } ?: true) }
    var timeTexts by remember(routine) {
        mutableStateOf(
            definition?.slots.orEmpty().associate { it.period to minutesText(it.reminderMinutes) },
        )
    }
    var afterCompletion by remember(routine) { mutableStateOf(definition?.schedule is RoutineSchedule.AfterCompletion) }
    var interval by remember(routine) {
        mutableStateOf((definition?.schedule as? RoutineSchedule.AfterCompletion)?.intervalDays?.toString() ?: "14")
    }
    val todayDate = LocalToday.current
    val today = todayDate.toString()
    val existingCalendar = definition?.schedule as? RoutineSchedule.Calendar
    var weekInterval by remember(routine) { mutableStateOf(existingCalendar?.weekInterval?.coerceAtLeast(1) ?: 1) }
    var weekdays by remember(routine) {
        mutableStateOf(existingCalendar?.weekdays?.ifEmpty { (1..7).toSet() } ?: (1..7).toSet())
    }
    val valid = name.isNotBlank() && periods.isNotEmpty() &&
        (afterCompletion || weekdays.isNotEmpty()) &&
        (!afterCompletion || interval.toIntOrNull()?.let { it > 0 } == true) &&
        (!reminders || periods.all { parseMinutes(timeTexts[it] ?: minutesText(defaultMinutes(it))) != null })

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().heightIn(max = 720.dp),
            shape = RoundedCornerShape(28.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(if (routine == null) "New routine" else "Edit routine", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    Text("TYPE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = kind == RoutineKind.HEALTH, onClick = {
                            if (kind != RoutineKind.HEALTH) {
                                periods = setOf(RoutinePeriod.MORNING)
                                weekdays = (1..7).toSet()
                                reminders = true
                            }
                            kind = RoutineKind.HEALTH
                            afterCompletion = false
                        }, label = { Text("Health") })
                        FilterChip(selected = kind == RoutineKind.CHORE, onClick = {
                            if (kind != RoutineKind.CHORE) {
                                periods = setOf(RoutinePeriod.HOME)
                                weekdays = setOf(todayDate.dayOfWeek.ordinal + 1)
                                reminders = false
                            }
                            kind = RoutineKind.CHORE
                        }, label = { Text("Chore") })
                    }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(if (kind == RoutineKind.HEALTH) "Name, e.g. Creatine" else "Name, e.g. Take out trash") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    AnimatedVisibility(kind == RoutineKind.HEALTH) {
                        Column {
                            Spacer(Modifier.height(10.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = subtype == HealthSubtype.SUPPLEMENT, onClick = { subtype = HealthSubtype.SUPPLEMENT }, label = { Text("Supplement") })
                                FilterChip(selected = subtype == HealthSubtype.MEDICATION, onClick = { subtype = HealthSubtype.MEDICATION }, label = { Text("Medication") })
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("Amount") }, singleLine = true, modifier = Modifier.weight(1f))
                                OutlinedTextField(value = unit, onValueChange = { unit = it }, label = { Text("Unit") }, singleLine = true, modifier = Modifier.weight(1f))
                            }
                        }
                    }

                    Spacer(Modifier.height(18.dp))
                    Text(if (kind == RoutineKind.HEALTH) "TIMES" else "WHEN", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val options = if (kind == RoutineKind.HEALTH) {
                            listOf(RoutinePeriod.MORNING, RoutinePeriod.AFTERNOON, RoutinePeriod.EVENING)
                        } else {
                            listOf(RoutinePeriod.ANYTIME, RoutinePeriod.HOME, RoutinePeriod.MORNING, RoutinePeriod.EVENING)
                        }
                        options.forEach { period ->
                            FilterChip(
                                selected = period in periods,
                                onClick = {
                                    periods = if (period in periods) periods - period else {
                                        if (kind == RoutineKind.CHORE) setOf(period) else periods + period
                                    }
                                },
                                label = { Text(periodLabel(period)) },
                            )
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Reminders", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (kind == RoutineKind.HEALTH) "At the start of each selected time" else "Optional for chores",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = reminders, onCheckedChange = { reminders = it })
                    }
                    AnimatedVisibility(reminders) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            periods.sortedBy { it.ordinal }.forEach { period ->
                                OutlinedTextField(
                                    value = timeTexts[period] ?: minutesText(defaultMinutes(period)),
                                    onValueChange = { value -> timeTexts = timeTexts + (period to value.take(5)) },
                                    label = { Text("${periodLabel(period)} reminder (HH:MM)") },
                                    supportingText = {
                                        if (parseMinutes(timeTexts[period] ?: minutesText(defaultMinutes(period))) == null) {
                                            Text("Use 24-hour time, e.g. 14:00")
                                        }
                                    },
                                    isError = parseMinutes(timeTexts[period] ?: minutesText(defaultMinutes(period))) == null,
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Text("REPEAT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (kind == RoutineKind.CHORE) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !afterCompletion, onClick = { afterCompletion = false }, label = { Text("On a schedule") })
                            FilterChip(selected = afterCompletion, onClick = { afterCompletion = true }, label = { Text("After completion") })
                        }
                    }
                    if (afterCompletion) {
                        OutlinedTextField(
                            value = interval,
                            onValueChange = { interval = it.filter(Char::isDigit) },
                            label = { Text("Days after completion") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        if (kind == RoutineKind.CHORE) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = weekInterval == 1, onClick = { weekInterval = 1 }, label = { Text("Weekly") })
                                FilterChip(selected = weekInterval == 2, onClick = { weekInterval = 2 }, label = { Text("Every 2 weeks") })
                            }
                        }
                        Text("Days of week", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { index, label ->
                                val day = index + 1
                                FilterChip(
                                    selected = day in weekdays,
                                    onClick = { weekdays = if (day in weekdays) weekdays - day else weekdays + day },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = valid && !saving,
                        onClick = {
                            val existingSlots = definition?.slots.orEmpty().associateBy { it.period }
                            val slots = periods.sortedBy { it.ordinal }.map { period ->
                                val old = existingSlots[period]
                                RoutineSlot(
                                    id = old?.id.orEmpty(),
                                    label = periodLabel(period),
                                    period = period,
                                    reminderMinutes = parseMinutes(timeTexts[period] ?: "")
                                        ?: old?.reminderMinutes
                                        ?: defaultMinutes(period),
                                    reminderEnabled = reminders,
                                    followUpMinutes = if (kind == RoutineKind.HEALTH && reminders) 30 else 0,
                                )
                            }
                            val schedule = if (afterCompletion) {
                                RoutineSchedule.AfterCompletion(
                                    intervalDays = interval.toIntOrNull() ?: 14,
                                    firstDueDate = (definition?.schedule as? RoutineSchedule.AfterCompletion)?.firstDueDate ?: today,
                                )
                            } else {
                                RoutineSchedule.Calendar(
                                    weekdays = if (weekdays.size == 7) emptySet() else weekdays,
                                    weekInterval = weekInterval,
                                    anchorDate = existingCalendar?.anchorDate ?: today,
                                )
                            }
                            onSave(
                                RoutineDraft(
                                    name = name,
                                    kind = kind,
                                    healthSubtype = if (kind == RoutineKind.HEALTH) subtype else null,
                                    amount = if (kind == RoutineKind.HEALTH) amount else "",
                                    unit = if (kind == RoutineKind.HEALTH) unit else "",
                                    iconName = if (kind == RoutineKind.HEALTH) "favorite" else "home",
                                    color = if (kind == RoutineKind.HEALTH) "#2E9D78" else "#5576D1",
                                    schedule = schedule,
                                    slots = slots,
                                ),
                            )
                        },
                    ) { Text(if (saving) "Saving…" else "Save") }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 2.dp),
    )
}

private fun kindColor(kind: RoutineKind): Color = if (kind == RoutineKind.HEALTH) HealthColor else ChoreColor

private fun periodLabel(period: RoutinePeriod): String = period.name.lowercase().replaceFirstChar { it.uppercase() }

private fun defaultMinutes(period: RoutinePeriod): Int = when (period) {
    RoutinePeriod.MORNING -> 8 * 60
    RoutinePeriod.AFTERNOON -> 14 * 60
    RoutinePeriod.EVENING -> 20 * 60
    RoutinePeriod.ANYTIME -> 9 * 60
    RoutinePeriod.HOME -> 18 * 60
}

private fun minutesText(minutes: Int): String =
    "${(minutes / 60).toString().padStart(2, '0')}:${(minutes % 60).toString().padStart(2, '0')}"

private fun parseMinutes(value: String): Int? {
    val parts = value.split(':')
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}

private fun routineSummary(routine: Routine): String {
    val definition = routine.definition
    val schedule = when (val value = definition.schedule) {
        is RoutineSchedule.Calendar -> {
            val days = if (value.weekdays.isEmpty()) "Daily" else "${value.weekdays.size} days a week"
            if (value.weekInterval > 1) "Every ${value.weekInterval} weeks · $days" else days
        }
        is RoutineSchedule.AfterCompletion -> "Every ${value.intervalDays} days after completion"
    }
    val times = definition.slots.joinToString(" + ") { periodLabel(it.period) }
    return listOf(schedule, times).filter { it.isNotBlank() }.joinToString(" · ")
}

/** What the screen shows while routines are turned off in Settings. */
@Composable
private fun RoutinesTurnedOff(onOpenDrawer: () -> Unit, onNavigateToSearch: () -> Unit) {
    Scaffold(
        topBar = {
            VicuTopAppBar(
                title = { Text("Routines") },
                onOpenDrawer = onOpenDrawer,
                onNavigateToSearch = onNavigateToSearch,
            )
        },
    ) { padding ->
        EmptyState(
            icon = Icons.Outlined.FavoriteBorder,
            title = "Routines are turned off",
            subtitle = "Turn them on in Settings to track health and home routines",
            modifier = Modifier.padding(padding),
        )
    }
}
