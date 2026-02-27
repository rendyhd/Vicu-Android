package com.rendyhd.vicu.ui.screens.today

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.components.picker.VicuDatePickerDialog
import com.rendyhd.vicu.ui.components.section.SectionHeader
import com.rendyhd.vicu.ui.components.shared.EmptyState
import com.rendyhd.vicu.ui.components.shared.VicuFab
import com.rendyhd.vicu.ui.components.shared.VicuTopAppBar
import com.rendyhd.vicu.ui.components.task.SwipeableTaskItem
import com.rendyhd.vicu.util.DateUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    onTaskClick: (Long) -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onShowTaskEntry: (Long?) -> Unit = {},
    viewModel: TodayViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var schedulingTask by remember { mutableStateOf<Task?>(null) }
    val listState = rememberLazyListState()

    Scaffold(
        topBar = {
            VicuTopAppBar(
                title = {
                    Column {
                        Text("Today")
                        Text(
                            text = DateUtils.formatTodaySubtitle(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                onOpenDrawer = onOpenDrawer,
                onNavigateToSearch = onNavigateToSearch,
            )
        },
        floatingActionButton = {
            VicuFab(
                onClick = { onShowTaskEntry(null) },
                listState = listState,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val allEmpty = state.overdueTasks.isEmpty() && state.todayTasks.isEmpty()
            val hasOverdue = state.overdueTasks.isNotEmpty()

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (allEmpty && !state.isLoading) {
                    item {
                        EmptyState(
                            icon = Icons.Outlined.WbSunny,
                            title = "All clear for today",
                            subtitle = "Enjoy the rest of your day",
                        )
                    }
                } else {
                    if (hasOverdue) {
                        item(key = "header_overdue") {
                            SectionHeader(
                                title = "Overdue",
                                color = Color(0xFFEF4444),
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        items(state.overdueTasks, key = { it.id }) { task ->
                            val displayTask = if (task.id in state.completedTaskIds) task.copy(done = true) else task
                            SwipeableTaskItem(
                                task = displayTask,
                                onToggleDone = {
                                    if (task.id in state.completedTaskIds) {
                                        viewModel.undoComplete(task)
                                    } else {
                                        viewModel.toggleDone(task)
                                    }
                                },
                                onClick = { onTaskClick(task.id) },
                                onSchedule = { schedulingTask = task },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }

                    if (hasOverdue && state.todayTasks.isNotEmpty()) {
                        item(key = "header_today") {
                            SectionHeader(
                                title = "Today",
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }

                    items(state.todayTasks, key = { it.id }) { task ->
                        val displayTask = if (task.id in state.completedTaskIds) task.copy(done = true) else task
                        SwipeableTaskItem(
                            task = displayTask,
                            onToggleDone = {
                                if (task.id in state.completedTaskIds) {
                                    viewModel.undoComplete(task)
                                } else {
                                    viewModel.toggleDone(task)
                                }
                            },
                            onClick = { onTaskClick(task.id) },
                            onSchedule = { schedulingTask = task },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }

    // Error snackbar with retry
    LaunchedEffect(state.error) {
        state.error?.let { msg ->
            val result = snackbarHostState.showSnackbar(
                message = msg,
                actionLabel = "Retry",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.refresh()
            }
            viewModel.clearError()
        }
    }

    schedulingTask?.let { task ->
        VicuDatePickerDialog(
            currentDate = task.dueDate,
            onDateSelected = { date ->
                viewModel.rescheduleTask(task, date)
                schedulingTask = null
            },
            onClearDate = {
                viewModel.rescheduleTask(task, "")
                schedulingTask = null
            },
            onDismiss = { schedulingTask = null },
        )
    }
}
