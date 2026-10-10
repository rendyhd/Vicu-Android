package com.rendyhd.vicu.ui.screens.project

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.ui.components.shared.FabClearance
import org.koin.compose.viewmodel.koinViewModel
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.components.section.SectionHeader
import com.rendyhd.vicu.ui.components.section.SectionLevel
import com.rendyhd.vicu.ui.components.selection.SelectionAction
import com.rendyhd.vicu.ui.components.selection.SelectionPickers
import com.rendyhd.vicu.ui.components.selection.SelectionTopBar
import com.rendyhd.vicu.ui.components.selection.SelectionViewModel
import com.rendyhd.vicu.ui.components.shared.ArchiveProjectDialog
import com.rendyhd.vicu.ui.components.shared.DeleteProjectDialog
import com.rendyhd.vicu.ui.components.shared.EmptyState
import com.rendyhd.vicu.ui.components.shared.ProjectEditDialog
import com.rendyhd.vicu.ui.components.shared.LocalFabAlignStart
import com.rendyhd.vicu.ui.components.shared.VicuFab
import com.rendyhd.vicu.ui.components.shared.VicuTopAppBar
import com.rendyhd.vicu.ui.components.shared.rememberVicuTopBarScroll
import com.rendyhd.vicu.ui.components.task.AddTaskButton
import com.rendyhd.vicu.ui.components.task.ReorderableTaskRow
import com.rendyhd.vicu.ui.components.task.SwipeableTaskItem
import com.rendyhd.vicu.ui.screens.shared.ProjectActions
import com.rendyhd.vicu.util.isManuallyOrdered
import com.rendyhd.vicu.util.moveOptions
import com.rendyhd.vicu.util.parseHexColor
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
    onProjectGone: () -> Unit = {},
    viewModel: ProjectViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val menu by viewModel.menu.collectAsStateWithLifecycle()
    val exit by viewModel.exit.collectAsStateWithLifecycle()
    val reviewUndo by viewModel.reviewUndo.collectAsStateWithLifecycle()
    // The options menu's open dialog, by name so a rotation keeps it.
    var openDialog by rememberSaveable { mutableStateOf<String?>(null) }

    // Rows kept on screen after completing them are let go when the screen is left.
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val topBarScroll = rememberVicuTopBarScroll(listState)

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
            haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) // a reorder step (design-system-v1, haptics)
        }
    }

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
                    title = { Text(state.project?.title ?: "Project") },
                    onOpenDrawer = onOpenDrawer,
                    scroll = topBarScroll,
                    onNavigateToSearch = onNavigateToSearch,
                    // No menu while the project is missing or archived.
                    trailingActions = {
                        if (menu.project != null) {
                            ProjectOptionsMenu(
                                entries = menu.entries(),
                                onChoose = { entry ->
                                    when (entry) {
                                        ProjectMenuEntry.EDIT -> openDialog = ProjectDialog.EDIT.name
                                        ProjectMenuEntry.ADD_SUBPROJECT -> openDialog = ProjectDialog.SUBPROJECT.name
                                        ProjectMenuEntry.SET_INBOX -> viewModel.setAsInbox()
                                        ProjectMenuEntry.MARK_REVIEWED -> viewModel.markReviewed()
                                        ProjectMenuEntry.ARCHIVE -> openDialog = ProjectDialog.ARCHIVE.name
                                        ProjectMenuEntry.DELETE -> if (menu.confirmBeforeDelete) {
                                            openDialog = ProjectDialog.DELETE.name
                                        } else {
                                            viewModel.delete()
                                        }
                                    }
                                },
                            )
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!selectionActive) {
                VicuFab(onClick = { onShowTaskEntry(projectId, null) }, expanded = !listState.canScrollBackward)
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

            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = FabClearance)) {
                if (allEmpty && !state.isLoading) {
                    item {
                        EmptyState(
                            icon = Icons.Outlined.Folder,
                            title = "No tasks",
                            subtitle = "Add a task to get started",
                        )
                    }
                } else {
                    // Unsectioned tasks (directly in parent project). Which rows can move a place
                    // (for a screen reader, which cannot drag): one pass.
                    val unsectionedMoves = moveOptions(state.unsectionedTasks)
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
                            onMoveUp = unsectionedMoves[task.id]?.takeIf { canDrag && it.up }
                                ?.let { { viewModel.moveTaskBy(task.id, -1); Unit } },
                            onMoveDown = unsectionedMoves[task.id]?.takeIf { canDrag && it.down }
                                ?.let { { viewModel.moveTaskBy(task.id, 1); Unit } },
                        )
                    }

                    // Add-task affordance for the parent project. Shown only when it has child
                    // projects, so adding directly to the parent stays distinct from opening a
                    // child row or using a section's add action. With no children the FAB covers it.
                    if (state.sections.isNotEmpty() || state.childProjects.isNotEmpty()) {
                        item(key = "add_task_parent", contentType = "add_task") {
                            AddTaskButton(
                                onClick = { onShowTaskEntry(projectId, null) },
                            )
                        }
                    }

                    items(state.childProjects, key = { "subproject_${it.id}" }, contentType = { "subproject" }) { project ->
                        SubprojectRow(
                            project = project,
                            enabled = !selectionActive,
                            onClick = { onProjectClick(project.id) },
                            modifier = Modifier.animateItem(),
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
                        onMoveTask = { task, offset -> viewModel.moveTaskBy(task.id, offset) },
                        onAddTask = { pid -> onShowTaskEntry(pid, null) },
                    )
                }
            }
        }
    }

    LaunchedEffect(state.error) {
        // Archiving or deleting from here makes the project "archived" or "not found" on its way
        // out; that is not an error to show.
        if (exit != ProjectExit.NONE) return@LaunchedEffect
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

    // Archived or deleted from the options menu: go back the way a deleted list does.
    LaunchedEffect(exit) {
        if (exit == ProjectExit.LEFT) onProjectGone()
    }

    // "Mark reviewed" offers Undo, the same snackbar as the review screen's.
    LaunchedEffect(reviewUndo) {
        val previous = reviewUndo ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = ProjectActions.markedReviewedMessage(previous),
            actionLabel = "Undo",
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undoReview() else viewModel.dismissReviewUndo()
    }

    val dismissDialog = { openDialog = null }
    val menuProject = menu.project
    if (menuProject != null) {
        when (openDialog?.let { name -> ProjectDialog.entries.firstOrNull { it.name == name } }) {
            ProjectDialog.EDIT -> ProjectEditDialog(
                project = menuProject,
                projects = menu.projects,
                onSave = { name, hexColor, parentId ->
                    viewModel.editProject(name, hexColor, parentId)
                    dismissDialog()
                },
                onDismiss = dismissDialog,
            )
            ProjectDialog.SUBPROJECT -> ProjectEditDialog(
                project = null,
                projects = menu.projects,
                initialParentId = menuProject.id,
                onSave = { name, hexColor, parentId ->
                    viewModel.addSubproject(name, hexColor, parentId)
                    dismissDialog()
                },
                onDismiss = dismissDialog,
            )
            ProjectDialog.ARCHIVE -> ArchiveProjectDialog(
                project = menuProject,
                onConfirm = {
                    viewModel.archive()
                    dismissDialog()
                },
                onDismiss = dismissDialog,
            )
            ProjectDialog.DELETE -> DeleteProjectDialog(
                project = menuProject,
                onConfirm = {
                    viewModel.delete()
                    dismissDialog()
                },
                onDismiss = dismissDialog,
            )
            null -> Unit
        }
    }

    SelectionPickers(
        selectionVm = selectionVm,
        action = selectionAction,
        selectedCount = selectedIds.size,
        onDismiss = { selectionAction = null },
    )
}

/** The dialogs the options menu opens. */
private enum class ProjectDialog { EDIT, SUBPROJECT, ARCHIVE, DELETE }

/** The top bar's "more" button and the project's options. */
@Composable
private fun ProjectOptionsMenu(
    entries: List<ProjectMenuEntry>,
    onChoose: (ProjectMenuEntry) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "Project options")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            entries.forEach { entry ->
                DropdownMenuItem(
                    text = {
                        Text(
                            entry.label,
                            color = if (entry.destructive) MaterialTheme.colorScheme.error else Color.Unspecified,
                        )
                    },
                    onClick = {
                        open = false
                        onChoose(entry)
                    },
                )
            }
        }
    }
}

@Composable
private fun SubprojectRow(
    project: Project,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Folder,
            contentDescription = null,
            tint = parseHexColor(project.hexColor)
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
    onMoveTask: (Task, Int) -> Unit,
    onAddTask: (Long) -> Unit,
) {
    sections.forEach { section ->
        item(key = "section_${section.project.id}", contentType = "header") {
            val sectionColor = parseHexColor(section.project.hexColor)
            // A sub-project inside the project being viewed: a level 2 header with its dot.
            SectionHeader(
                title = section.project.title,
                level = SectionLevel.TWO,
                dotColor = sectionColor,
                count = openTaskCount(section, completedTaskIds),
                isExpanded = section.isExpanded,
                onToggle = { onSectionToggle(section.project.id) },
                modifier = Modifier.padding(start = (depth * 16).dp),
            )
        }

        if (section.isExpanded) {
            val moves = moveOptions(section.tasks)
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
                    onMoveUp = moves[task.id]?.takeIf { canDrag && it.up }
                        ?.let { { onMoveTask(task, -1) } },
                    onMoveDown = moves[task.id]?.takeIf { canDrag && it.down }
                        ?.let { { onMoveTask(task, 1) } },
                )
            }

            item(key = "add_task_section_${section.project.id}", contentType = "add_task") {
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
                onMoveTask = onMoveTask,
                onAddTask = onAddTask,
            )
        }
    }
}
