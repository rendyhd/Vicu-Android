package com.rendyhd.vicu.ui.screens.project

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.components.section.CollapsibleSection
import com.rendyhd.vicu.ui.components.selection.SelectionAction
import com.rendyhd.vicu.ui.components.selection.SelectionPickers
import com.rendyhd.vicu.ui.components.selection.SelectionTopBar
import com.rendyhd.vicu.ui.components.selection.SelectionViewModel
import com.rendyhd.vicu.ui.components.shared.EmptyState
import com.rendyhd.vicu.ui.components.shared.LocalFabAlignStart
import com.rendyhd.vicu.ui.components.shared.VicuFab
import com.rendyhd.vicu.ui.components.shared.VicuTopAppBar
import com.rendyhd.vicu.ui.components.task.AddTaskButton
import com.rendyhd.vicu.ui.components.task.SwipeableTaskItem
import com.rendyhd.vicu.util.isManuallyOrdered
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(
    projectId: Long,
    onTaskClick: (Long) -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onShowTaskEntry: (Long?, String?) -> Unit = { _, _ -> },
    onProjectClick: (Long) -> Unit = {},
    viewModel: ProjectViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    // Rows kept on screen after completing them are let go when the screen is left.
    DisposableEffect(viewModel) { onDispose { viewModel.completions.releaseAll() } }
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    val haptic = LocalHapticFeedback.current
    // True once the current long-press drag has actually displaced the row. A lift that
    // never moves falls through to selection mode (multi-select keeps its entry point).
    var dragMoved by remember { mutableStateOf(false) }
    // dragMoved is the shared one-drag-at-a-time sentinel: set true here when a
    // displacement lands, read by whichever row's onDragStopped owns the active drag
    // (the library serializes drags, so there is exactly one).
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        val fromId = from.key as? Long
        val toId = to.key as? Long
        if (fromId != null && toId != null && viewModel.onTaskMoved(fromId, toId)) {
            dragMoved = true
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    val selectionVm: SelectionViewModel = koinViewModel()
    val selectedIds by selectionVm.selectedIds.collectAsState()
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
                    title = { Text(state.project?.title ?: "Project") },
                    onOpenDrawer = onOpenDrawer,
                    onNavigateToSearch = onNavigateToSearch,
                )
            }
        },
        floatingActionButton = {
            if (!selectionActive) {
                VicuFab(onClick = { onShowTaskEntry(projectId, null) })
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
            val allEmpty = state.unsectionedTasks.isEmpty() &&
                state.childProjects.isEmpty() &&
                !hasAnyTask(state.sections)

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
                    items(state.unsectionedTasks, key = { it.id }, contentType = { "task" }) { task ->
                        val displayTask = if (task.id in state.completedTaskIds) task.copy(done = true) else task
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
                            // Draggable rows enter selection via lift-without-move
                            // (onDragStopped above); the rest keep plain long-press.
                            onLongClick = if (canDrag) null else ({ selectionVm.toggle(task.id) }),
                        )
                    }

                    // Add-task affordance for the parent project. Shown only when it has child
                    // projects, so adding directly to the parent stays distinct from opening a
                    // child row or using a section's add action. With no children the FAB covers it.
                    if (state.sections.isNotEmpty() || state.childProjects.isNotEmpty()) {
                        item(key = "add_task_parent") {
                            AddTaskButton(
                                onClick = { onShowTaskEntry(projectId, null) },
                            )
                        }
                    }

                    items(state.childProjects, key = { "subproject_${it.id}" }) { project ->
                        SubprojectRow(
                            project = project,
                            enabled = !selectionActive,
                            onClick = { onProjectClick(project.id) },
                        )
                    }

                    // Sections (child projects) — recursive renderer handles arbitrary nesting
                    projectSectionItems(
                        sections = state.sections,
                        depth = 0,
                        reorderableState = reorderableState,
                        completedTaskIds = state.completedTaskIds,
                        selectedIds = selectedIds,
                        selectionActive = selectionActive,
                        onSectionToggle = { pid -> viewModel.toggleSection(pid) },
                        onDragStarted = { dragMoved = false },
                        onDragStopped = { task ->
                            if (dragMoved) viewModel.onTaskDropped(task.id) else selectionVm.toggle(task.id)
                        },
                        onToggleDone = { task ->
                            if (task.id in state.completedTaskIds) viewModel.undoComplete(task) else viewModel.toggleDone(task)
                        },
                        onRowClick = { task ->
                            if (selectionActive) selectionVm.toggle(task.id) else onTaskClick(task.id)
                        },
                        onSchedule = { task -> viewModel.scheduleTask(task.id) },
                        onLongClickToggle = { task -> selectionVm.toggle(task.id) },
                        onAddTask = { pid -> onShowTaskEntry(pid, null) },
                    )
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
        action = selectionAction,
        selectedCount = selectedIds.size,
        onDismiss = { selectionAction = null },
    )
}

@Composable
private fun SubprojectRow(
    project: Project,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Folder,
            contentDescription = null,
            tint = parseSectionColor(project.hexColor)
                ?: MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = project.title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun LazyItemScope.ReorderableTaskRow(
    reorderableState: ReorderableLazyListState,
    task: Task,
    displayTask: Task,
    canDrag: Boolean,
    selectionActive: Boolean,
    selected: Boolean,
    onDragStarted: () -> Unit,
    onDragStopped: () -> Unit,
    onToggleDone: () -> Unit,
    onClick: () -> Unit,
    onSubtaskToggleDone: (Task) -> Unit,
    onSubtaskClick: (Task) -> Unit,
    onSchedule: () -> Unit,
    onLongClick: (() -> Unit)?,
    contentStartPadding: Dp = 0.dp,
) {
    val haptic = LocalHapticFeedback.current
    ReorderableItem(reorderableState, key = task.id) { isDragging ->
        val elevation by animateDpAsState(
            if (isDragging) 4.dp else 0.dp,
            label = "dragElevation",
        )
        // The Surface stays in the tree even when idle: swapping it in/out on isDragging
        // would change the slot structure and reset the row's internal state mid-drag.
        Surface(
            shadowElevation = elevation,
            color = if (isDragging) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                Color.Transparent
            },
        ) {
            SwipeableTaskItem(
                task = displayTask,
                onToggleDone = onToggleDone,
                onClick = onClick,
                onSubtaskToggleDone = onSubtaskToggleDone,
                onSubtaskClick = onSubtaskClick,
                onSchedule = onSchedule,
                selectionActive = selectionActive,
                selected = selected,
                onLongClick = onLongClick,
                contentStartPadding = contentStartPadding,
                modifier = if (canDrag) {
                    Modifier.longPressDraggableHandle(
                        onDragStarted = {
                            onDragStarted()
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDragStopped = onDragStopped,
                    )
                } else {
                    Modifier
                },
            )
        }
    }
}

private fun parseSectionColor(hex: String): Color? =
    try {
        if (hex.isNotBlank()) {
            Color(android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex"))
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }

private fun LazyListScope.projectSectionItems(
    sections: List<ProjectSection>,
    depth: Int,
    reorderableState: ReorderableLazyListState,
    completedTaskIds: Set<Long>,
    selectedIds: Set<Long>,
    selectionActive: Boolean,
    onSectionToggle: (Long) -> Unit,
    onDragStarted: () -> Unit,
    onDragStopped: (Task) -> Unit,
    onToggleDone: (Task) -> Unit,
    onRowClick: (Task) -> Unit,
    onSchedule: (Task) -> Unit,
    onLongClickToggle: (Task) -> Unit,
    onAddTask: (Long) -> Unit,
) {
    sections.forEach { section ->
        item(key = "section_${section.project.id}") {
            val sectionColor = parseSectionColor(section.project.hexColor)
            CollapsibleSection(
                title = section.project.title,
                color = sectionColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                taskCount = totalTaskCount(section),
                isExpanded = section.isExpanded,
                onToggle = { onSectionToggle(section.project.id) },
                modifier = Modifier.padding(start = (depth * 16).dp),
            )
        }

        if (section.isExpanded) {
            items(section.tasks, key = { it.id }, contentType = { "task" }) { task ->
                val displayTask = if (task.id in completedTaskIds) task.copy(done = true) else task
                val canDrag = !selectionActive &&
                    task.id !in completedTaskIds &&
                    isManuallyOrdered(task)
                ReorderableTaskRow(
                    reorderableState = reorderableState,
                    task = task,
                    displayTask = displayTask,
                    canDrag = canDrag,
                    selectionActive = selectionActive,
                    selected = task.id in selectedIds,
                    onDragStarted = onDragStarted,
                    onDragStopped = { onDragStopped(task) },
                    onToggleDone = { onToggleDone(task) },
                    onClick = { onRowClick(task) },
                    onSubtaskToggleDone = onToggleDone,
                    onSubtaskClick = onRowClick,
                    onSchedule = { onSchedule(task) },
                    onLongClick = if (canDrag) null else ({ onLongClickToggle(task) }),
                    contentStartPadding = ((depth + 1) * 16).dp,
                )
            }

            item(key = "add_task_section_${section.project.id}") {
                AddTaskButton(
                    onClick = { onAddTask(section.project.id) },
                    modifier = Modifier.padding(start = ((depth + 1) * 16).dp),
                )
            }

            projectSectionItems(
                sections = section.children,
                depth = depth + 1,
                reorderableState = reorderableState,
                completedTaskIds = completedTaskIds,
                selectedIds = selectedIds,
                selectionActive = selectionActive,
                onSectionToggle = onSectionToggle,
                onDragStarted = onDragStarted,
                onDragStopped = onDragStopped,
                onToggleDone = onToggleDone,
                onRowClick = onRowClick,
                onSchedule = onSchedule,
                onLongClickToggle = onLongClickToggle,
                onAddTask = onAddTask,
            )
        }
    }
}
