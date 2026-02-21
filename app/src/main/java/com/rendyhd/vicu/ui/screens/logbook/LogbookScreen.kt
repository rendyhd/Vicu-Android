package com.rendyhd.vicu.ui.screens.logbook

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.rendyhd.vicu.ui.components.shared.EmptyState
import com.rendyhd.vicu.ui.components.shared.VicuTopAppBar
import com.rendyhd.vicu.ui.components.task.TaskItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogbookScreen(
    onTaskClick: (Long) -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    viewModel: LogbookViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            VicuTopAppBar(
                title = { Text("Logbook") },
                onOpenDrawer = onOpenDrawer,
                onNavigateToSearch = onNavigateToSearch,
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
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (state.tasks.isEmpty() && !state.isLoading) {
                    item {
                        EmptyState(
                            icon = Icons.Outlined.CheckCircle,
                            title = "No completed tasks",
                            subtitle = "Tasks you complete will appear here",
                        )
                    }
                } else {
                    items(state.tasks, key = { it.id }) { task ->
                        TaskItem(
                            task = if (task.id in state.uncompletedTaskIds) task.copy(done = false) else task,
                            onToggleDone = {
                                if (task.id in state.uncompletedTaskIds) {
                                    viewModel.undoUncomplete(task)
                                } else {
                                    viewModel.toggleDone(task)
                                }
                            },
                            onClick = { onTaskClick(task.id) },
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

    LaunchedEffect(state.uncompletedTaskIds) {
        val lastId = state.uncompletedTaskIds.lastOrNull() ?: return@LaunchedEffect
        val task = state.tasks.find { it.id == lastId } ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Task uncompleted",
            actionLabel = "Undo",
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoUncomplete(task)
        }
    }
}
