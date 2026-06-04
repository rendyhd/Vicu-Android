package com.rendyhd.vicu.ui.screens.customlist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.rendyhd.vicu.ui.components.selection.SelectionPickers
import com.rendyhd.vicu.ui.components.selection.SelectionTopBar
import com.rendyhd.vicu.ui.components.selection.SelectionViewModel
import com.rendyhd.vicu.ui.components.shared.CustomListDialog
import com.rendyhd.vicu.ui.components.shared.EmptyState
import com.rendyhd.vicu.ui.components.shared.VicuFab
import com.rendyhd.vicu.ui.components.shared.VicuTopAppBar
import com.rendyhd.vicu.ui.components.task.SwipeableTaskItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomListScreen(
    listId: String,
    onTaskClick: (Long) -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onShowTaskEntry: (Long?, String?) -> Unit = { _, _ -> },
    viewModel: CustomListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val projects by viewModel.projects.collectAsState()
    val labels by viewModel.labels.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showEditDialog by remember { mutableStateOf(false) }
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
                    title = { Text(state.customList?.name ?: "List") },
                    onOpenDrawer = onOpenDrawer,
                    onNavigateToSearch = onNavigateToSearch,
                    extraActions = {
                        IconButton(onClick = { showEditDialog = true }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit list")
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!selectionActive) {
                val addToProject = state.customList?.filter?.addToProjectId?.takeIf { it != 0L }
                VicuFab(onClick = { onShowTaskEntry(addToProject, null) })
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
                if (state.tasks.isEmpty() && !state.isLoading) {
                    item {
                        EmptyState(
                            icon = Icons.Outlined.FilterList,
                            title = "No matching tasks",
                            subtitle = "Try adjusting the list filters",
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

    // Edit list dialog
    if (showEditDialog && state.customList != null) {
        CustomListDialog(
            customList = state.customList,
            projects = projects,
            labels = labels,
            onSave = { updated ->
                viewModel.saveCustomList(updated)
                showEditDialog = false
            },
            onDismiss = { showEditDialog = false },
            inboxProjectId = state.inboxProjectId,
        )
    }
}
