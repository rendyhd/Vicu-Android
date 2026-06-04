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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.rendyhd.vicu.ui.components.section.CollapsibleSection
import com.rendyhd.vicu.ui.components.shared.EmptyState
import com.rendyhd.vicu.ui.components.shared.VicuFab
import com.rendyhd.vicu.ui.components.shared.VicuTopAppBar
import com.rendyhd.vicu.ui.components.task.SwipeableTaskItem
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.parseHexColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    onTaskClick: (Long) -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onShowTaskEntry: (Long?, String?) -> Unit = { _, _ -> },
    viewModel: TodayViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
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
                onClick = { onShowTaskEntry(null, DateUtils.todayEndIso()) },
            )
        },
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
                if (state.projectGroups.isEmpty() && !state.isLoading) {
                    item {
                        EmptyState(
                            icon = Icons.Outlined.WbSunny,
                            title = "All clear for today",
                            subtitle = "Enjoy the rest of your day",
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
                                    onClick = { onTaskClick(task.id) },
                                    onSchedule = { viewModel.scheduleTask(task) },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
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
                viewModel.refresh(true)
            }
            viewModel.clearError()
        }
    }
}
