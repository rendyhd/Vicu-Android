package com.rendyhd.vicu.ui.screens.project

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
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
import com.rendyhd.vicu.ui.components.section.CollapsibleSection
import com.rendyhd.vicu.ui.components.shared.EmptyState
import com.rendyhd.vicu.ui.components.shared.VicuFab
import com.rendyhd.vicu.ui.components.shared.VicuTopAppBar
import com.rendyhd.vicu.ui.components.task.SwipeableTaskItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(
    projectId: Long,
    onTaskClick: (Long) -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onShowTaskEntry: (Long?) -> Unit = {},
    viewModel: ProjectViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var schedulingTask by remember { mutableStateOf<Task?>(null) }
    val listState = rememberLazyListState()

    Scaffold(
        topBar = {
            VicuTopAppBar(
                title = { Text(state.project?.title ?: "Project") },
                onOpenDrawer = onOpenDrawer,
                onNavigateToSearch = onNavigateToSearch,
            )
        },
        floatingActionButton = {
            VicuFab(
                onClick = { onShowTaskEntry(projectId) },
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
            val allEmpty = state.unsectionedTasks.isEmpty() &&
                state.sections.all { it.tasks.isEmpty() }

            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                if (allEmpty && !state.isLoading) {
                    item {
                        EmptyState(
                            icon = Icons.Outlined.Folder,
                            title = "No tasks",
                            subtitle = "Add a task to get started",
                        )
                    }
                } else {
                    // Unsectioned tasks (directly in parent project)
                    items(state.unsectionedTasks, key = { it.id }) { task ->
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

                    // Sections (child projects)
                    state.sections.forEachIndexed { index, section ->
                        val sectionColor = try {
                            val hex = section.project.hexColor
                            if (hex.isNotBlank()) {
                                Color(
                                    android.graphics.Color.parseColor(
                                        if (hex.startsWith("#")) hex else "#$hex"
                                    )
                                )
                            } else {
                                null
                            }
                        } catch (_: Exception) {
                            null
                        }

                        item(key = "section_${section.project.id}") {
                            CollapsibleSection(
                                title = section.project.title,
                                color = sectionColor
                                    ?: MaterialTheme.colorScheme.onSurfaceVariant,
                                taskCount = section.tasks.size,
                                isExpanded = section.isExpanded,
                                onToggle = { viewModel.toggleSection(index) },
                            )
                        }

                        if (section.isExpanded) {
                            items(section.tasks, key = { it.id }) { task ->
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
                                    modifier = Modifier
                                        .padding(start = 16.dp)
                                        .animateItem(),
                                )
                            }
                        }
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
