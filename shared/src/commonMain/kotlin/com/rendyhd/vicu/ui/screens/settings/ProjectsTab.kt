package com.rendyhd.vicu.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.ui.components.task.moveCustomActions
import com.rendyhd.vicu.ui.navigation.LongPressReorderableRow
import com.rendyhd.vicu.ui.navigation.ProjectRow
import com.rendyhd.vicu.ui.navigation.buildProjectTree
import com.rendyhd.vicu.ui.navigation.moveProjectRow
import com.rendyhd.vicu.ui.navigation.siblingIds
import com.rendyhd.vicu.ui.navigation.visibleProjectRows
import com.rendyhd.vicu.util.moveIdBy
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState

private const val PROJECT_KEY_PREFIX = "settings_project/"

/** The lazy item key of an active project's row: the rows that can be dragged. */
internal fun settingsProjectKey(projectId: Long): String = "$PROJECT_KEY_PREFIX$projectId"

/** The project a lazy item [key] stands for, or null for any other row. */
internal fun settingsProjectId(key: Any?): Long? =
    (key as? String)?.takeIf { it.startsWith(PROJECT_KEY_PREFIX) }?.removePrefix(PROJECT_KEY_PREFIX)?.toLongOrNull()

/**
 * The Projects tab: the Inbox, the project tree (drag to reorder, a menu per project), the archived
 * projects, how projects are shown, review, labels and custom lists. One list, like the other tabs;
 * dialogs are opened through [openDialog] and drawn by [SettingsDialogHost].
 *
 * [projectRows] is the tree as [SettingsViewModel.projectRows] has it. While a row is dragged the
 * tab draws the order being made; at the drop the view model takes over and keeps the new order on
 * show until it is stored.
 */
@Composable
internal fun ProjectsTab(
    state: SettingsUiState,
    projectRows: List<ProjectRow>,
    archivedExpanded: Boolean,
    onArchivedExpandedChange: (Boolean) -> Unit,
    viewModel: SettingsViewModel,
    openDialog: (SettingsDialog) -> Unit,
    listState: LazyListState,
) {
    val haptic = LocalHapticFeedback.current
    var liveRows by remember { mutableStateOf<List<ProjectRow>?>(null) }
    val rows = liveRows ?: projectRows
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val sourceId = settingsProjectId(from.key) ?: return@rememberReorderableLazyListState
        val targetId = settingsProjectId(to.key) ?: return@rememberReorderableLazyListState
        val moved = moveProjectRow(liveRows ?: projectRows, sourceId, targetId)
        if (moved != null) {
            liveRows = moved
            haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) // a reorder step (design-system-v1, haptics)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
    ) {
        inboxSection(state, viewModel, openDialog)
        projectsSection(
            state = state,
            rows = rows,
            reorderState = reorderState,
            onDragStopped = { projectId ->
                liveRows?.let { viewModel.reorderProject(projectId, siblingIds(it, projectId)) }
                liveRows = null
            },
            viewModel = viewModel,
            openDialog = openDialog,
        )
        archivedProjectsSection(state, archivedExpanded, onArchivedExpandedChange, viewModel, openDialog)
        item(key = "projects_divider") {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }
        displaySection(state, viewModel)
        reviewSection(state, viewModel, openDialog)
        labelsSection(state, viewModel, openDialog)
        customListsSection(state, viewModel, openDialog)
        item(key = "projects_tab_bottom_spacer") {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/** Deletes [project], asking first when "Confirm before deleting" is on. */
private fun deleteProject(
    project: Project,
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    openDialog: (SettingsDialog) -> Unit,
) {
    if (state.behaviorPrefs.confirmBeforeDelete) {
        openDialog(SettingsDialog.DeleteProject(project.id))
    } else {
        viewModel.deleteProject(project.id)
    }
}

/** What a project's "more" menu offers. The Inbox can be edited and given subprojects, nothing else. */
internal fun projectMenuItems(
    project: Project,
    isInbox: Boolean,
    onEdit: () -> Unit,
    onAddSubproject: () -> Unit,
    onSetInbox: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
): List<RowMenuItem> = buildList {
    add(RowMenuItem("Edit", onClick = onEdit))
    add(RowMenuItem("Add subproject", onClick = onAddSubproject))
    if (!isInbox) {
        add(RowMenuItem("Set as Inbox", onClick = onSetInbox))
        add(RowMenuItem("Archive", onClick = onArchive))
        add(RowMenuItem("Delete", destructive = true, onClick = onDelete))
    }
}

private fun LazyListScope.projectsSection(
    state: SettingsUiState,
    rows: List<ProjectRow>,
    reorderState: ReorderableLazyListState,
    onDragStopped: (projectId: Long) -> Unit,
    viewModel: SettingsViewModel,
    openDialog: (SettingsDialog) -> Unit,
) {
    item(key = "projects_header") {
        SectionTitle(
            title = "Projects",
            onAdd = { openDialog(SettingsDialog.ProjectEditor(null)) },
        )
    }

    item(key = "projects_hint") {
        Text(
            text = "Tap a project to edit it, hold it to drag, or use ⋮ for more.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
        )
    }

    if (rows.isEmpty()) {
        item(key = "projects_empty") {
            Text(
                text = "No projects yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        return
    }

    // What a screen reader (which cannot drag) moves by: the ids of each level in display order.
    val siblings = rows.groupBy({ it.parentId }, { it.project.id })
    items(rows, key = { settingsProjectKey(it.project.id) }, contentType = { "project" }) { row ->
        val project = row.project
        val isInbox = project.id == state.inboxProjectId
        val levelIds = siblings[row.parentId].orEmpty()
        LongPressReorderableRow(
            reorderState = reorderState,
            key = settingsProjectKey(project.id),
            onDragStopped = { onDragStopped(project.id) },
        ) {
            SettingsProjectRow(
                project = project,
                depth = row.depth,
                isInbox = isInbox,
                onEdit = { openDialog(SettingsDialog.ProjectEditor(project.id)) },
                menuItems = projectMenuItems(
                    project = project,
                    isInbox = isInbox,
                    onEdit = { openDialog(SettingsDialog.ProjectEditor(project.id)) },
                    onAddSubproject = { openDialog(SettingsDialog.SubprojectEditor(project.id)) },
                    onSetInbox = { viewModel.setInboxProject(project.id) },
                    onArchive = { openDialog(SettingsDialog.ArchiveProject(project.id)) },
                    onDelete = { deleteProject(project, state, viewModel, openDialog) },
                ),
                modifier = Modifier.moveCustomActions(
                    onMoveUp = moveIdBy(levelIds, project.id, -1)
                        ?.let { order -> { viewModel.reorderProject(project.id, order) } },
                    onMoveDown = moveIdBy(levelIds, project.id, 1)
                        ?.let { order -> { viewModel.reorderProject(project.id, order) } },
                ),
            )
        }
    }
}

/** The archived projects: one row that opens and closes them, shown only when there are any. */
private fun LazyListScope.archivedProjectsSection(
    state: SettingsUiState,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    viewModel: SettingsViewModel,
    openDialog: (SettingsDialog) -> Unit,
) {
    if (state.archivedProjects.isEmpty()) return

    item(key = "archived_projects_group") {
        ArchivedGroupRow(
            count = state.archivedProjects.size,
            expanded = expanded,
            onToggle = { onExpandedChange(!expanded) },
        )
    }

    if (expanded) {
        val archivedRows = visibleProjectRows(buildProjectTree(state.archivedProjects), emptySet())
        items(archivedRows, key = { "archived_project_${it.project.id}" }, contentType = { "archived_project" }) { row ->
            val project = row.project
            ArchivedProjectRow(
                project = project,
                depth = row.depth,
                onRestore = { viewModel.restoreProject(project) },
                menuItems = buildList {
                    add(RowMenuItem("Restore", onClick = { viewModel.restoreProject(project) }))
                    // An Inbox archived elsewhere is not deleted from here, as before.
                    if (project.id != state.inboxProjectId) {
                        add(
                            RowMenuItem("Delete", destructive = true, onClick = {
                                deleteProject(project, state, viewModel, openDialog)
                            }),
                        )
                    }
                },
            )
        }
    }
}
