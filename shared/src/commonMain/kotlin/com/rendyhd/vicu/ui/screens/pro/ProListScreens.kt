package com.rendyhd.vicu.ui.screens.pro

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.components.pro.LocalPro
import com.rendyhd.vicu.ui.components.pro.PersonMark
import com.rendyhd.vicu.ui.components.pro.ProHiddenSummary
import com.rendyhd.vicu.ui.components.pro.ProIcons
import com.rendyhd.vicu.ui.components.pro.ProViewSwitch
import com.rendyhd.vicu.ui.components.section.ProjectMeta
import com.rendyhd.vicu.ui.components.section.SectionHeader
import com.rendyhd.vicu.ui.components.section.openCount
import com.rendyhd.vicu.ui.components.section.showsGroupHeader
import com.rendyhd.vicu.ui.components.shared.EmptyState
import com.rendyhd.vicu.ui.components.shared.LocalDateFormat
import com.rendyhd.vicu.ui.components.shared.VicuDragHandle
import com.rendyhd.vicu.ui.components.shared.VicuSnackbarHost
import com.rendyhd.vicu.ui.components.shared.VicuTopAppBar
import com.rendyhd.vicu.ui.components.shared.listScrollbar
import com.rendyhd.vicu.ui.components.shared.rememberVicuTopBarScroll
import com.rendyhd.vicu.ui.components.task.SwipeableTaskItem
import com.rendyhd.vicu.ui.navigation.ScreenReady
import com.rendyhd.vicu.util.parseHexColor
import com.rendyhd.vicu.util.pro.AvailabilityView
import com.rendyhd.vicu.util.pro.ProScreens
import org.koin.compose.viewmodel.koinViewModel

/** The frame of a Pro screen: the top bar, the error snackbar and one list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProScreenScaffold(
    title: String,
    onOpenDrawer: () -> Unit,
    onNavigateToSearch: () -> Unit,
    error: String? = null,
    onErrorShown: () -> Unit = {},
    trailingActions: @Composable (androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    ScreenReady(true)
    val listState = rememberLazyListState()
    val topBarScroll = rememberVicuTopBarScroll()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(error) {
        if (error != null) {
            snackbarHostState.showSnackbar(error)
            onErrorShown()
        }
    }
    Scaffold(
        modifier = topBarScroll.modifier,
        topBar = {
            VicuTopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                onOpenDrawer = onOpenDrawer,
                scroll = topBarScroll,
                onNavigateToSearch = onNavigateToSearch,
                trailingActions = trailingActions,
            )
        },
        snackbarHost = { VicuSnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding).listScrollbar(listState),
            contentPadding = PaddingValues(bottom = 24.dp),
            content = content,
        )
    }
}

/** A Pro page opened while Pro (or its layer) is off, from a link or the history: says so, changes nothing. */
@Composable
private fun ProOffNotice(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "This is part of Pro, which is off on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onOpenSettings) { Text("Pro settings") }
    }
}

/** A task row of a Pro screen: completing keeps it in place for a moment, as on every list. */
@Composable
private fun ProTaskRow(
    task: Task,
    held: Set<Long>,
    viewModel: ProTaskListViewModel,
    onTaskClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    projectMeta: ProjectMeta? = null,
) {
    SwipeableTaskItem(
        task = if (task.id in held) task.copy(done = true) else task,
        onToggleDone = { if (task.id in held) viewModel.undoComplete(task) else viewModel.toggleDone(task) },
        onClick = { onTaskClick(task.id) },
        onSubtaskToggleDone = viewModel::toggleDone,
        onSubtaskClick = { child -> onTaskClick(child.id) },
        modifier = modifier,
        projectMeta = projectMeta,
    )
}

/** Flagged (contract 21.4): open flagged tasks by project, Available by default. */
@Composable
fun FlaggedScreen(
    onTaskClick: (Long) -> Unit,
    onOpenDrawer: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: FlaggedViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val held by viewModel.completedIds.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    ProScreenScaffold("Flagged", onOpenDrawer, onNavigateToSearch, error, viewModel::clearError) {
        if (!state.on) {
            item(key = "off") { ProOffNotice(onOpenSettings) }
        } else {
            item(key = "view_switch") { ProViewSwitch(state.view, viewModel::setView) }
            if (state.groups.isEmpty() && state.hidden == 0) {
                item(key = "empty") {
                    EmptyState(
                        icon = ProIcons.FLAGGED,
                        title = "Nothing flagged",
                        subtitle = "Flag a task in its editor, or type !flag when you add it",
                    )
                }
            }
            state.groups.forEach { group ->
                val hasHeader = showsGroupHeader(group.tasks.size)
                if (hasHeader) {
                    item(key = "group_${group.projectId}", contentType = "header") {
                        SectionHeader(
                            title = group.title,
                            dotColor = parseHexColor(group.hexColor),
                            count = openCount(group.tasks, held),
                        )
                    }
                }
                val meta = if (hasHeader) null else ProjectMeta(group.title, group.hexColor)
                items(group.tasks, key = { it.id }, contentType = { "task" }) { task ->
                    ProTaskRow(task, held, viewModel, onTaskClick, Modifier.animateItem(), meta)
                }
            }
            if (state.view == AvailabilityView.AVAILABLE) {
                item(key = "hidden") { ProHiddenSummary(state.hidden, onShow = { viewModel.setView(AvailabilityView.REMAINING) }) }
            }
        }
    }
}

/** Waiting on (contract 21.6): promises of theirs and tasks with an on-hold label, by person. */
@Composable
fun WaitingOnScreen(
    onTaskClick: (Long) -> Unit,
    onOpenDrawer: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPerson: (Long) -> Unit,
    viewModel: WaitingOnViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val held by viewModel.completedIds.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    ProScreenScaffold("Waiting on", onOpenDrawer, onNavigateToSearch, error, viewModel::clearError) {
        if (!state.on) {
            item(key = "off") { ProOffNotice(onOpenSettings) }
        } else if (state.groups.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    icon = ProIcons.WAITING_ON,
                    title = "Nothing to wait for",
                    subtitle = "Type “Jan: send the deck” to note what someone promised you",
                )
            }
        } else {
            state.groups.forEach { group ->
                val groupKey = group.person ?: 0L
                item(key = "group_$groupKey", contentType = "header") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { SectionHeader(title = group.name, count = group.tasks.size) }
                        if (group.person != null) {
                            TextButton(onClick = { onOpenPerson(group.person) }, modifier = Modifier.padding(end = 8.dp)) {
                                Text("Open ${group.name}")
                            }
                        }
                    }
                }
                items(group.tasks, key = { it.id }, contentType = { "task" }) { task ->
                    ProTaskRow(task, held, viewModel, onTaskClick, Modifier.animateItem())
                }
            }
        }
    }
}

/** People (contract 21.5): who you work with, what is open both ways, the next due first. */
@Composable
fun PeopleScreen(
    onOpenDrawer: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPerson: (Long) -> Unit,
) {
    val pro = LocalPro.current
    val world = pro.world
    val rows = remember(world) { ProScreens.peopleRows(world.people, world.open, world::promiseFor, world.today, world.zone) }
    val candidates = remember(world) { world.labels.filter { !world.isPerson(it.id) }.sortedBy { it.title.lowercase() } }
    ProScreenScaffold("People", onOpenDrawer, onNavigateToSearch) {
        if (!world.promisesOn) {
            item(key = "off") { ProOffNotice(onOpenSettings) }
            return@ProScreenScaffold
        }
        if (rows.isEmpty()) {
            item(key = "empty") {
                Text(
                    "No people yet. A person is a label: add one below, or open a label and choose This is a person.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                )
            }
        }
        items(rows, key = { "person_${it.id}" }) { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(role = Role.Button, onClickLabel = "Open") { onOpenPerson(row.id) }
                    .padding(start = 4.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PersonMark(name = row.name, hexColor = row.hexColor, modifier = Modifier.padding(horizontal = 12.dp))
                Text(
                    row.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${row.theirs} from them, ${row.mine} from you",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (row.overdue > 0) {
                        Text("${row.overdue} overdue", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(start = 48.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
        if (candidates.isNotEmpty()) {
            item(key = "add_person") {
                var open by remember { mutableStateOf(false) }
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                    OutlinedButton(onClick = { open = true }) { Text("Add a person") }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        candidates.forEach { label ->
                            DropdownMenuItem(
                                text = { Text(label.title) },
                                onClick = {
                                    open = false
                                    pro.actions?.setPerson(label.id, true)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A person's page (contract 21.5): the pattern of the last 90 days, the next 1:1's agenda, what
 * they promised you, what you owe them and what to confirm; the agenda and a recap go through
 * the share sheet.
 */
@Composable
fun PersonScreen(
    onTaskClick: (Long) -> Unit,
    onOpenDrawer: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: PersonViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val held by viewModel.completedIds.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var recapOpen by rememberSaveable { mutableStateOf(false) }
    val name = state.label?.title ?: "Person"
    ProScreenScaffold(name, onOpenDrawer, onNavigateToSearch, error, viewModel::clearError) {
        when {
            !state.on -> item(key = "off") { ProOffNotice(onOpenSettings) }
            state.label != null && !state.isPerson -> item(key = "not_person") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "This label is not marked as a person.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = viewModel::markAsPerson) { Text("This is a person") }
                }
            }
            else -> {
                item(key = "header") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PersonMark(name = name, hexColor = state.label?.hexColor.orEmpty())
                            Spacer(Modifier.width(8.dp))
                            Text(
                                state.pattern,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = viewModel::shareAgenda) {
                                Icon(Icons.Outlined.Share, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Share agenda")
                            }
                            OutlinedButton(onClick = { recapOpen = true }) {
                                Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Recap")
                            }
                        }
                    }
                }
                item(key = "agenda_header", contentType = "header") {
                    SectionHeader(title = "Next 1:1", count = state.agenda.items.size)
                }
                items(state.agenda.items, key = { "agenda_$it" }) { line ->
                    Text(
                        "- $line",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
                personSection("theirs", "From $name", state.sections.theirs, held, viewModel, onTaskClick)
                personSection("mine", "You owe $name", state.sections.mine, held, viewModel, onTaskClick)
                personSection("confirm", "To confirm", state.sections.confirm, held, viewModel, onTaskClick)
            }
        }
    }
    if (recapOpen) {
        RecapSheet(viewModel = viewModel, onDismiss = { recapOpen = false })
    }
}

private fun LazyListScope.personSection(
    key: String,
    title: String,
    tasks: List<Task>,
    held: Set<Long>,
    viewModel: ProTaskListViewModel,
    onTaskClick: (Long) -> Unit,
) {
    if (tasks.isEmpty()) return
    item(key = "section_$key", contentType = "header") {
        SectionHeader(title = title, count = openCount(tasks, held))
    }
    items(tasks, key = { "${key}_${it.id}" }, contentType = { "task" }) { task ->
        ProTaskRow(task, held, viewModel, onTaskClick, Modifier.animateItem())
    }
}

/**
 * The recap (contract 21.8) for one person: the days it covers, what you owe and their words as
 * choices, a preview, and Share, which marks the promises in it as sent (decision 7).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RecapSheet(viewModel: PersonViewModel, onDismiss: () -> Unit) {
    val fmt = LocalDateFormat.current
    var days by rememberSaveable { mutableStateOf(7) }
    var includeMine by rememberSaveable { mutableStateOf(true) }
    var includeQuotes by rememberSaveable { mutableStateOf(true) }
    val world = LocalPro.current.world
    val recap = remember(days, includeMine, includeQuotes, fmt, world) { viewModel.recap(days, includeMine, includeQuotes, fmt) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { VicuDragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Recap", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1 to "Today", 7 to "Last 7 days", 30 to "Last 30 days").forEach { (value, label) ->
                    FilterChip(selected = days == value, onClick = { days = value }, label = { Text(label) })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = includeMine, onClick = { includeMine = !includeMine }, label = { Text("What I owe") })
                FilterChip(selected = includeQuotes, onClick = { includeQuotes = !includeQuotes }, label = { Text("Their words") })
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(recap.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.weight(1f))
                Button(onClick = {
                    viewModel.shareRecap(recap)
                    onDismiss()
                }) { Text("Share") }
            }
        }
    }
}
