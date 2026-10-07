package com.rendyhd.vicu.ui.screens.today

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import com.rendyhd.vicu.ui.components.section.CollapsibleSection
import com.rendyhd.vicu.ui.components.selection.SelectionAction
import com.rendyhd.vicu.ui.components.selection.SelectionPickers
import com.rendyhd.vicu.ui.components.selection.SelectionTopBar
import com.rendyhd.vicu.ui.components.selection.SelectionViewModel
import com.rendyhd.vicu.ui.components.shared.EmptyState
import com.rendyhd.vicu.ui.components.shared.LocalFabAlignStart
import com.rendyhd.vicu.ui.components.shared.LocalClockDay
import com.rendyhd.vicu.ui.components.shared.LocalToday
import com.rendyhd.vicu.ui.components.shared.VicuFab
import com.rendyhd.vicu.ui.components.shared.VicuTopAppBar
import com.rendyhd.vicu.ui.components.task.SwipeableTaskItem
import com.rendyhd.vicu.ui.screens.routines.RoutineOccurrenceRow
import com.rendyhd.vicu.ui.screens.shared.TaskProjectGroup
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.util.parseHexColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    onTaskClick: (Long) -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onShowTaskEntry: (Long?, String?) -> Unit = { _, _ -> },
    onOpenRoutines: () -> Unit = {},
    viewModel: TodayViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Rows kept on screen after completing them are let go when the screen is left.
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    val selectionVm: SelectionViewModel = koinViewModel()
    val selectedIds by selectionVm.selectedIds.collectAsStateWithLifecycle()
    val selectionActive = selectedIds.isNotEmpty()
    var selectionAction by remember { mutableStateOf<SelectionAction?>(null) }
    BackHandler(enabled = selectionActive) { selectionVm.clear() }

    Scaffold(
        topBar = {
            if (selectionActive) {
                SelectionTopBar(
                    count = selectedIds.size,
                    onClose = { selectionVm.clear() },
                    onToday = { selectionVm.bulkToday() },
                    onComplete = { selectionVm.bulkComplete(viewModel.completions) },
                    onSchedule = { selectionAction = SelectionAction.SCHEDULE },
                    onSetPriority = { selectionAction = SelectionAction.SET_PRIORITY },
                    onMove = { selectionAction = SelectionAction.MOVE_PROJECT },
                    onApplyLabel = { selectionAction = SelectionAction.APPLY_LABEL },
                    onRemove = { selectionAction = SelectionAction.REMOVE },
                )
            } else {
                VicuTopAppBar(
                    title = {
                        Column {
                            Text("Today")
                            Text(
                                text = DateUtils.formatTodaySubtitle(LocalToday.current),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onOpenDrawer = onOpenDrawer,
                    onNavigateToSearch = onNavigateToSearch,
                )
            }
        },
        floatingActionButton = {
            if (!selectionActive) {
                val day = LocalClockDay.current
                VicuFab(onClick = { onShowTaskEntry(null, DueDates.today(day.date, day.zone).toString()) })
            }
        },
        floatingActionButtonPosition = if (LocalFabAlignStart.current) FabPosition.Start else FabPosition.End,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { viewModel.refresh(showSpinner = true) },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (state.routineDay.occurrences.isNotEmpty()) {
                    item(key = "routine_header", contentType = "header") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Routines · ${state.routineDay.completedCount}/${state.routineDay.scheduledCount}",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onOpenRoutines) { Text("Manage") }
                        }
                    }
                    items(state.routineDay.occurrences, key = { "routine_${it.key}" }, contentType = { "occurrence" }) { occurrence ->
                        RoutineOccurrenceRow(
                            occurrence = occurrence,
                            onToggle = { viewModel.toggleRoutine(occurrence) },
                            onSkip = { viewModel.skipRoutine(occurrence) },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp).animateItem(),
                        )
                    }
                }

                if (state.projectGroups.isEmpty() && state.overdueGroups.isEmpty() &&
                    state.routineDay.occurrences.isEmpty() && !state.isLoading
                ) {
                    item {
                        EmptyState(
                            icon = Icons.Outlined.WbSunny,
                            title = "All clear for today",
                            subtitle = "Enjoy the rest of your day",
                        )
                    }
                } else {
                    // Overdue (local date before today) sits above Today; each section has its
                    // own project groups. The Today title only shows when it follows Overdue.
                    val showSectionTitles = state.overdueGroups.isNotEmpty()
                    if (showSectionTitles) {
                        item(key = "overdue_title", contentType = "title") {
                            TodaySectionTitle(
                                title = "Overdue",
                                count = state.overdueGroups.sumOf { it.tasks.size },
                                color = OverdueColor,
                            )
                        }
                        taskGroupItems(
                            groups = state.overdueGroups,
                            keyPrefix = "overdue",
                            onToggleGroup = viewModel::toggleOverdueProject,
                            state = state,
                            viewModel = viewModel,
                            selectionVm = selectionVm,
                            selectedIds = selectedIds,
                            onTaskClick = onTaskClick,
                        )
                    }
                    if (showSectionTitles && state.projectGroups.isNotEmpty()) {
                        item(key = "today_title", contentType = "title") {
                            TodaySectionTitle(
                                title = "Today",
                                count = state.projectGroups.sumOf { it.tasks.size },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    taskGroupItems(
                        groups = state.projectGroups,
                        keyPrefix = "today",
                        onToggleGroup = viewModel::toggleProject,
                        state = state,
                        viewModel = viewModel,
                        selectionVm = selectionVm,
                        selectedIds = selectedIds,
                        onTaskClick = onTaskClick,
                    )
                }
            }
        }
    }

    SelectionPickers(
        selectionVm = selectionVm,
        action = selectionAction,
        selectedCount = selectedIds.size,
        onDismiss = { selectionAction = null },
    )

    // Error snackbar with retry
    LaunchedEffect(state.error) {
        state.error?.let { msg ->
            val result = snackbarHostState.showSnackbar(
                message = msg,
                actionLabel = "Retry",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.refresh(true)
            }
            viewModel.clearError()
        }
    }
}

private val OverdueColor = Color(0xFFEF4444)

/** "Overdue" / "Today": a section title above that section's project groups. */
@Composable
private fun TodaySectionTitle(title: String, count: Int, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = color,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

/** One collapsible group per project with its task rows; keys carry [keyPrefix] so sections never collide. */
private fun LazyListScope.taskGroupItems(
    groups: List<TaskProjectGroup>,
    keyPrefix: String,
    onToggleGroup: (Long) -> Unit,
    state: TodayUiState,
    viewModel: TodayViewModel,
    selectionVm: SelectionViewModel,
    selectedIds: Set<Long>,
    onTaskClick: (Long) -> Unit,
) {
    val selectionActive = selectedIds.isNotEmpty()
    groups.forEach { group ->
        item(key = "${keyPrefix}_header_${group.projectId}", contentType = "header") {
            CollapsibleSection(
                title = group.title,
                color = parseHexColor(group.hexColor)
                    ?: MaterialTheme.colorScheme.onSurfaceVariant,
                taskCount = group.tasks.size,
                isExpanded = group.isExpanded,
                onToggle = { onToggleGroup(group.projectId) },
            )
        }
        if (group.isExpanded) {
            items(group.tasks, key = { it.id }, contentType = { "task" }) { task ->
                val displayTask =
                    if (task.id in state.completedTaskIds) task.copy(done = true) else task
                SwipeableTaskItem(
                    task = displayTask,
                    onToggleDone = {
                        if (task.id in state.completedTaskIds) {
                            viewModel.undoComplete(task)
                        } else {
                            viewModel.toggleDone(task)
                        }
                    },
                    onClick = {
                        if (selectionActive) {
                            selectionVm.toggle(task.id)
                        } else {
                            onTaskClick(task.id)
                        }
                    },
                    onSubtaskToggleDone = viewModel::toggleDone,
                    onSubtaskClick = { child -> onTaskClick(child.id) },
                    onSchedule = { viewModel.scheduleTask(task.id) },
                    selectionActive = selectionActive,
                    selected = task.id in selectedIds,
                    onLongClick = { selectionVm.toggle(task.id) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}
