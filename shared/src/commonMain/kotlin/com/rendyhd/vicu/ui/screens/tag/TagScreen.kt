package com.rendyhd.vicu.ui.screens.tag

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.rendyhd.vicu.ui.components.section.ProjectMeta
import com.rendyhd.vicu.ui.components.section.SectionHeader
import com.rendyhd.vicu.ui.components.section.openCount
import com.rendyhd.vicu.ui.components.section.showsGroupHeader
import com.rendyhd.vicu.ui.components.shared.FabClearance
import com.rendyhd.vicu.util.parseHexColor
import org.koin.compose.viewmodel.koinViewModel
import com.rendyhd.vicu.ui.components.selection.SelectionAction
import com.rendyhd.vicu.ui.components.selection.SelectionPickers
import com.rendyhd.vicu.ui.components.selection.SelectionTopBar
import com.rendyhd.vicu.ui.components.selection.SelectionViewModel
import com.rendyhd.vicu.ui.components.shared.EmptyState
import com.rendyhd.vicu.ui.components.shared.LocalFabAlignStart
import com.rendyhd.vicu.ui.components.shared.VicuFab
import com.rendyhd.vicu.ui.components.shared.VicuTopAppBar
import com.rendyhd.vicu.ui.components.shared.rememberVicuTopBarScroll
import com.rendyhd.vicu.ui.components.task.SwipeableTaskItem
import com.rendyhd.vicu.ui.components.task.RowView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagScreen(
    labelId: Long,
    onTaskClick: (Long) -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onShowTaskEntry: (Long?, String?) -> Unit = { _, _ -> },
    viewModel: TagViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Rows kept on screen after completing them are let go when the screen is left.
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val topBarScroll = rememberVicuTopBarScroll()

    val selectionVm: SelectionViewModel = koinViewModel()
    val selectedIds by selectionVm.selectedIds.collectAsStateWithLifecycle()
    val selectionActive = selectedIds.isNotEmpty()
    var selectionAction by remember { mutableStateOf<SelectionAction?>(null) }
    BackHandler(enabled = selectionActive) { selectionVm.clear() }

    Scaffold(

        modifier = topBarScroll.modifier,
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
                    title = { Text(state.label?.title ?: "Label") },
                    onOpenDrawer = onOpenDrawer,
                    scroll = topBarScroll,
                    onNavigateToSearch = onNavigateToSearch,
                )
            }
        },
        floatingActionButton = {
            if (!selectionActive) {
                VicuFab(onClick = { onShowTaskEntry(null, null) }, expanded = !listState.canScrollBackward)
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
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = FabClearance)) {
                if (state.tasks.isEmpty() && !state.isLoading) {
                    item {
                        EmptyState(
                            icon = Icons.AutoMirrored.Outlined.Label,
                            title = "No tasks with this tag",
                        )
                    }
                } else {
                    state.groups.forEach { group ->
                        // A group of one task has no header: its project goes on the row's meta line.
                        val hasHeader = showsGroupHeader(group.tasks.size)
                        val projectMeta = if (hasHeader) null else ProjectMeta(group.title, group.hexColor)
                        if (hasHeader) {
                            item(key = "group_${group.projectId}", contentType = "header") {
                                SectionHeader(
                                    title = group.title,
                                    dotColor = parseHexColor(group.hexColor),
                                    count = openCount(group.tasks, state.completedTaskIds),
                                )
                            }
                        }
                        items(group.tasks, key = { it.id }, contentType = { "task" }) { task ->
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
                                onSubtaskToggleDone = viewModel::toggleDone,
                                onSubtaskClick = { child -> onTaskClick(child.id) },
                                onSchedule = { viewModel.scheduleTask(task.id) },
                                selectionActive = selectionActive,
                                selected = task.id in selectedIds,
                                onLongClick = { selectionVm.toggle(task.id) },
                                modifier = Modifier.animateItem(),
                                projectMeta = projectMeta,
                                rowView = RowView(labelId = labelId),
                            )
                        }
                    }
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
