package com.rendyhd.vicu.ui.screens.anytime

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AllInclusive
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
import com.rendyhd.vicu.ui.components.section.CollapsibleSection
import com.rendyhd.vicu.ui.components.selection.SelectionPickers
import com.rendyhd.vicu.ui.components.selection.SelectionTopBar
import com.rendyhd.vicu.ui.components.selection.SelectionViewModel
import com.rendyhd.vicu.ui.components.shared.EmptyState
import com.rendyhd.vicu.ui.components.shared.VicuFab
import com.rendyhd.vicu.ui.components.shared.VicuTopAppBar
import com.rendyhd.vicu.ui.components.task.SwipeableTaskItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnytimeScreen(
    onTaskClick: (Long) -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onShowTaskEntry: (Long?, String?) -> Unit = { _, _ -> },
    viewModel: AnytimeViewModel = hiltViewModel(),
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
                    title = { Text("Anytime") },
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
                            icon = Icons.Outlined.AllInclusive,
                            title = "No open tasks",
                            subtitle = "All tasks are in your inbox",
                        )
                    }
                } else {
                    state.projectGroups.forEachIndexed { projectIndex, group ->
                        val projectColor = try {
                            val hex = group.project.hexColor
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

                        val totalTasks = group.unsectionedTasks.size +
                            group.sections.sumOf { it.tasks.size }

                        // Project header (collapsible)
                        item(key = "header_${group.project.id}") {
                            CollapsibleSection(
                                title = group.project.title,
                                color = projectColor
                                    ?: MaterialTheme.colorScheme.onSurfaceVariant,
                                taskCount = totalTasks,
                                isExpanded = group.isExpanded,
                                onToggle = { viewModel.toggleProject(projectIndex) },
                            )
                        }

                        if (group.isExpanded) {
                            // Unsectioned tasks (directly in parent project)
                            items(group.unsectionedTasks, key = { it.id }) { task ->
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
                                    onClick = {
                                        if (selectionActive) selectionVm.toggle(task.id) else onTaskClick(task.id)
                                    },
                                    onSchedule = { viewModel.scheduleTask(task) },
                                    selectionActive = selectionActive,
                                    selected = task.id in selectedIds,
                                    onLongClick = { selectionVm.toggle(task.id) },
                                    modifier = Modifier.animateItem(),
                                )
                            }

                            // Child project sections (collapsible, indented)
                            group.sections.forEachIndexed { sectionIndex, section ->
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
                                        onToggle = { viewModel.toggleSection(projectIndex, sectionIndex) },
                                        modifier = Modifier.padding(start = 16.dp),
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
                                            onClick = {
                                                if (selectionActive) selectionVm.toggle(task.id) else onTaskClick(task.id)
                                            },
                                            onSchedule = { viewModel.scheduleTask(task) },
                                            selectionActive = selectionActive,
                                            selected = task.id in selectedIds,
                                            onLongClick = { selectionVm.toggle(task.id) },
                                            modifier = Modifier.animateItem(),
                                            contentStartPadding = 16.dp,
                                        )
                                    }
                                }
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
                viewModel.refresh(true)
            }
            viewModel.clearError()
        }
    }

    SelectionPickers(
        selectionVm = selectionVm,
        showMove = showMovePicker,
        showLabel = showLabelPicker,
        onDismissMove = { showMovePicker = false },
        onDismissLabel = { showLabelPicker = false },
    )
}
