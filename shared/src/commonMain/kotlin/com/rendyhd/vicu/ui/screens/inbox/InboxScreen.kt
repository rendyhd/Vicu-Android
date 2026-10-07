package com.rendyhd.vicu.ui.screens.inbox

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inbox
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import org.koin.compose.viewmodel.koinViewModel
import com.rendyhd.vicu.ui.components.selection.SelectionAction
import com.rendyhd.vicu.ui.components.selection.SelectionPickers
import com.rendyhd.vicu.ui.components.selection.SelectionTopBar
import com.rendyhd.vicu.ui.components.selection.SelectionViewModel
import com.rendyhd.vicu.ui.components.shared.EmptyState
import com.rendyhd.vicu.ui.components.shared.LocalFabAlignStart
import com.rendyhd.vicu.ui.components.shared.VicuFab
import com.rendyhd.vicu.ui.components.shared.VicuTopAppBar
import com.rendyhd.vicu.ui.components.task.ReorderableTaskRow
import com.rendyhd.vicu.util.isManuallyOrdered
import com.rendyhd.vicu.util.moveOptions
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    onTaskClick: (Long) -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onShowTaskEntry: (Long?, String?) -> Unit = { _, _ -> },
    viewModel: InboxViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Rows kept on screen after completing them are let go when the screen is left.
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    val haptic = LocalHapticFeedback.current
    // True once the current long-press drag has actually displaced the row; a lift that never
    // moves falls through to selection mode (the library serializes drags, so one flag is enough).
    var dragMoved by remember { mutableStateOf(false) }
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        val fromId = from.key as? Long
        val toId = to.key as? Long
        if (fromId != null && toId != null && viewModel.onTaskMoved(fromId, toId)) {
            dragMoved = true
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

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
                    title = { Text("Inbox") },
                    onOpenDrawer = onOpenDrawer,
                    onNavigateToSearch = onNavigateToSearch,
                )
            }
        },
        floatingActionButton = {
            // Only a standing notice (no usable Inbox) hides it; a failed refresh or completion
            // is a passing message and the user can still add a task.
            if (!selectionActive && state.notice == null) {
                VicuFab(onClick = { onShowTaskEntry(state.inboxProjectId, null) })
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
            // Which rows can move a place (for a screen reader, which cannot drag): one pass.
            val moves = remember(state.tasks) { moveOptions(state.tasks) }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                if (state.tasks.isEmpty() && !state.isLoading) {
                    item {
                        EmptyState(
                            icon = Icons.Outlined.Inbox,
                            title = if (state.notice != null) "Inbox unavailable" else "Inbox is empty",
                            subtitle = state.notice ?: "Tasks without a project appear here",
                        )
                    }
                } else {
                    items(state.tasks, key = { it.id }, contentType = { "task" }) { task ->
                        val displayTask = if (task.id in state.completedTaskIds) task.copy(done = true) else task
                        // Undated tasks are ordered by hand: a long press that moves the row drags it.
                        val canDrag = !selectionActive &&
                            task.id !in state.completedTaskIds &&
                            isManuallyOrdered(task)
                        ReorderableTaskRow(
                            reorderableState = reorderableState,
                            task = task,
                            displayTask = displayTask,
                            canDrag = canDrag,
                            selectionActive = selectionActive,
                            selected = task.id in selectedIds,
                            onDragStarted = { dragMoved = false },
                            onDragStopped = {
                                if (dragMoved) {
                                    viewModel.onTaskDropped(task.id)
                                } else {
                                    // Lifted without moving: that is how a draggable row is selected.
                                    selectionVm.toggle(task.id)
                                }
                            },
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
                            onLongClick = if (canDrag) null else ({ selectionVm.toggle(task.id) }),
                            onMoveUp = moves[task.id]?.takeIf { canDrag && it.up }
                                ?.let { { viewModel.moveTaskBy(task.id, -1); Unit } },
                            onMoveDown = moves[task.id]?.takeIf { canDrag && it.down }
                                ?.let { { viewModel.moveTaskBy(task.id, 1); Unit } },
                        )
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
                viewModel.retry()
            } else {
                viewModel.clearError()
            }
        }
    }
}
