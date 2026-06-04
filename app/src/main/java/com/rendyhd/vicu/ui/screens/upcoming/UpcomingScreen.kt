package com.rendyhd.vicu.ui.screens.upcoming

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.rendyhd.vicu.ui.components.section.CollapsibleSection
import com.rendyhd.vicu.ui.components.selection.SelectionPickers
import com.rendyhd.vicu.ui.components.selection.SelectionTopBar
import com.rendyhd.vicu.ui.components.selection.SelectionViewModel
import com.rendyhd.vicu.ui.components.shared.EmptyState
import com.rendyhd.vicu.ui.components.shared.LocalFabAlignStart
import com.rendyhd.vicu.ui.components.shared.VicuFab
import com.rendyhd.vicu.ui.components.shared.VicuTopAppBar
import com.rendyhd.vicu.ui.components.task.SwipeableTaskItem
import com.rendyhd.vicu.util.parseHexColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpcomingScreen(
    onTaskClick: (Long) -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onShowTaskEntry: (Long?, String?) -> Unit = { _, _ -> },
    viewModel: UpcomingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    val selectionVm: SelectionViewModel = hiltViewModel()
    val selectedIds by selectionVm.selectedIds.collectAsState()
    val selectionActive = selectedIds.isNotEmpty()
    var showMovePicker by remember { mutableStateOf(false) }
    var showLabelPicker by remember { mutableStateOf(false) }
    BackHandler(enabled = selectionActive) { selectionVm.clear() }

    Scaffold(
        topBar = {
            if (selectionActive) {
                SelectionTopBar(
                    count = selectedIds.size,
                    onClose = { selectionVm.clear() },
                    onComplete = { selectionVm.bulkComplete() },
                    onMove = { showMovePicker = true },
                    onSchedule = { selectionVm.bulkSchedule() },
                    onApplyLabel = { showLabelPicker = true },
                )
            } else {
                VicuTopAppBar(
                    title = { Text("Upcoming") },
                    onOpenDrawer = onOpenDrawer,
                    onNavigateToSearch = onNavigateToSearch,
                )
            }
        },
        floatingActionButton = {
            if (!selectionActive) {
                VicuFab(onClick = { onShowTaskEntry(null, null) })
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
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                if (state.projectGroups.isEmpty() && !state.isLoading) {
                    item {
                        EmptyState(
                            icon = Icons.Outlined.CalendarMonth,
                            title = "Nothing upcoming",
                            subtitle = "Tasks with future due dates appear here",
                        )
                    }
                } else {
                    state.projectGroups.forEach { group ->
                        item(key = "header_${group.projectId}") {
                            CollapsibleSection(
                                title = group.title,
                                color = parseHexColor(group.hexColor)
                                    ?: MaterialTheme.colorScheme.onSurfaceVariant,
                                taskCount = group.tasks.size,
                                isExpanded = group.isExpanded,
                                onToggle = { viewModel.toggleProject(group.projectId) },
                            )
                        }
                        if (group.isExpanded) {
                            items(group.tasks, key = { it.id }) { task ->
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
                                    onSchedule = { viewModel.scheduleTask(task) },
                                    selectionActive = selectionActive,
                                    selected = task.id in selectedIds,
                                    onLongClick = { selectionVm.toggle(task.id) },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    SelectionPickers(
        selectionVm = selectionVm,
        showMove = showMovePicker,
        showLabel = showLabelPicker,
        onDismissMove = { showMovePicker = false },
        onDismissLabel = { showLabelPicker = false },
    )

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
