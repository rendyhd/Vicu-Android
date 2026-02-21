package com.rendyhd.vicu.ui.screens.tag

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.components.picker.VicuDatePickerDialog
import com.rendyhd.vicu.ui.components.shared.EmptyState
import com.rendyhd.vicu.ui.components.shared.VicuFab
import com.rendyhd.vicu.ui.components.shared.VicuTopAppBar
import com.rendyhd.vicu.ui.components.task.SwipeableTaskItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagScreen(
    labelId: Long,
    onTaskClick: (Long) -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onShowTaskEntry: (Long?) -> Unit = {},
    viewModel: TagViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var schedulingTask by remember { mutableStateOf<Task?>(null) }
    val listState = rememberLazyListState()

    Scaffold(
        topBar = {
            VicuTopAppBar(
                title = { Text(state.label?.title ?: "Tag") },
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
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                if (state.tasks.isEmpty() && !state.isLoading) {
                    item {
                        EmptyState(
                            icon = Icons.AutoMirrored.Outlined.Label,
                            title = "No tasks with this tag",
                        )
                    }
                } else {
                    items(state.tasks, key = { it.id }) { task ->
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

    LaunchedEffect(state.completedTaskIds) {
        val lastId = state.completedTaskIds.lastOrNull() ?: return@LaunchedEffect
        val task = state.tasks.find { it.id == lastId } ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Task completed",
            actionLabel = "Undo",
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoComplete(task)
        }
    }

    // Date picker for swipe-to-schedule
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
