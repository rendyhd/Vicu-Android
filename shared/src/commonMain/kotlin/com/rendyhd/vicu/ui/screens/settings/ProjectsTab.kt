package com.rendyhd.vicu.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import org.koin.compose.viewmodel.koinViewModel
import com.rendyhd.vicu.ui.components.task.moveCustomActions
import com.rendyhd.vicu.ui.components.category.CategoryHeader
import com.rendyhd.vicu.ui.components.category.CategoryLabels
import com.rendyhd.vicu.ui.navigation.LongPressReorderableRow
import com.rendyhd.vicu.ui.navigation.ProjectListItem
import com.rendyhd.vicu.ui.navigation.buildProjectTree
import com.rendyhd.vicu.ui.navigation.droppedPlace
import com.rendyhd.vicu.ui.navigation.indexOfCategory
import com.rendyhd.vicu.ui.navigation.indexOfProject
import com.rendyhd.vicu.ui.navigation.levelSiblings
import com.rendyhd.vicu.ui.navigation.moveProjectItem
import com.rendyhd.vicu.ui.navigation.visibleProjectRows
import com.rendyhd.vicu.ui.navigation.withoutDescendants
import com.rendyhd.vicu.util.ProjectCategories
import com.rendyhd.vicu.util.ProjectCategoryState
import com.rendyhd.vicu.ui.screens.routines.RoutineSharing
import com.rendyhd.vicu.ui.screens.routines.rememberSharedRoutineLookup
import com.rendyhd.vicu.util.moveIdBy
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState

private const val PROJECT_KEY_PREFIX = "settings_project/"
private const val CATEGORY_KEY_PREFIX = "settings_category/"

/** The lazy item key of an active project's row: the rows that can be dragged. */
internal fun settingsProjectKey(projectId: Long): String = "$PROJECT_KEY_PREFIX$projectId"

/** The project a lazy item [key] stands for, or null for any other row. */
internal fun settingsProjectId(key: Any?): Long? =
    (key as? String)?.takeIf { it.startsWith(PROJECT_KEY_PREFIX) }?.removePrefix(PROJECT_KEY_PREFIX)?.toLongOrNull()

/** The lazy item key of a category's header: a place a dragged project can go, never dragged itself. */
internal fun settingsCategoryKey(categoryId: String): String = "$CATEGORY_KEY_PREFIX$categoryId"

/** The category a lazy item [key] stands for, or null for any other row. */
internal fun settingsCategoryId(key: Any?): String? =
    (key as? String)?.takeIf { it.startsWith(CATEGORY_KEY_PREFIX) }?.removePrefix(CATEGORY_KEY_PREFIX)?.takeIf { it.isNotEmpty() }

/**
 * The Projects tab: the Inbox, the project tree under the headers of the user's categories (drag to
 * reorder, also past a header into that category; a menu per project and per category), the
 * archived projects, how projects are shown, review, labels and custom lists. One list, like the
 * other tabs; dialogs are opened through [openDialog] and drawn by [SettingsDialogHost].
 *
 * [projectItems] is the tree as [SettingsViewModel.projectItems] has it. While a row is dragged the
 * tab draws the order being made; at the drop the view model takes over and keeps the new order on
 * show until it is stored.
 */
@Composable
internal fun ProjectsTab(
    state: SettingsUiState,
    projectItems: List<ProjectListItem>,
    categories: ProjectCategoryState,
    archivedExpanded: Boolean,
    onArchivedExpandedChange: (Boolean) -> Unit,
    viewModel: SettingsViewModel,
    openDialog: (SettingsDialog) -> Unit,
    listState: LazyListState,
) {
    val haptic = LocalHapticFeedback.current
    val sharedRoutines = rememberSharedRoutineLookup()
    var liveItems by remember { mutableStateOf<List<ProjectListItem>?>(null) }
    val items = liveItems ?: projectItems
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val sourceId = settingsProjectId(from.key) ?: return@rememberReorderableLazyListState
        val current = liveItems ?: projectItems
        val targetIndex = settingsCategoryId(to.key)?.let { current.indexOfCategory(it) }
            ?: settingsProjectId(to.key)?.let { current.indexOfProject(it) }
            ?: return@rememberReorderableLazyListState
        val moved = moveProjectItem(current, sourceId, targetIndex)
        if (moved != null) {
            liveItems = moved
            haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) // a reorder step (design-system-v1, haptics)
        }
    }

    val savedFilters: SavedFilterImportViewModel = koinViewModel()
    SavedFilterSheetHost(savedFilters)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
    ) {
        inboxSection(state, viewModel, openDialog)
        projectsSection(
            state = state,
            items = items,
            categories = categories,
            reorderState = reorderState,
            // Carried as one row: its subprojects would be left behind, rows to pass.
            onDragStarted = { projectId -> liveItems = (liveItems ?: projectItems).withoutDescendants(projectId) },
            onDragStopped = { projectId ->
                liveItems?.let { dropped ->
                    droppedPlace(dropped, projectId)?.let { (categoryId, ids) -> viewModel.moveProject(projectId, categoryId, ids) }
                }
                liveItems = null
            },
            viewModel = viewModel,
            openDialog = openDialog,
            sharedRoutines = sharedRoutines,
        )
        archivedProjectsSection(state, archivedExpanded, onArchivedExpandedChange, viewModel, openDialog, sharedRoutines)
        item(key = "projects_divider") {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }
        displaySection(state, viewModel, openDialog)
        reviewSection(state, viewModel, openDialog)
        labelsSection(state, viewModel, openDialog)
        customListsSection(state, viewModel, openDialog, savedFilters)
        item(key = "projects_tab_bottom_spacer") {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * Deletes [project], asking first when "Confirm before deleting" is on, and always when it holds
 * shared routines ([sharedRoutines]), which go for everyone (section 6.9).
 */
private fun deleteProject(
    project: Project,
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    openDialog: (SettingsDialog) -> Unit,
    sharedRoutines: (Long) -> List<String>,
) {
    if (RoutineSharing.asksBeforeDeleting(state.behaviorPrefs.confirmBeforeDelete, sharedRoutines(project.id))) {
        openDialog(SettingsDialog.DeleteProject(project.id))
    } else {
        viewModel.deleteProject(project.id)
    }
}

/**
 * What a project's "more" menu offers. The Inbox can be edited and given subprojects, nothing else.
 * [onMoveToCategory] and [onAddCategoryAbove] are there for a top-level project (section 13).
 */
internal fun projectMenuItems(
    project: Project,
    isInbox: Boolean,
    onEdit: () -> Unit,
    onAddSubproject: () -> Unit,
    onSetInbox: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onMoveToCategory: (() -> Unit)? = null,
    onAddCategoryAbove: (() -> Unit)? = null,
): List<RowMenuItem> = buildList {
    add(RowMenuItem("Edit", onClick = onEdit))
    add(RowMenuItem("Add subproject", onClick = onAddSubproject))
    onMoveToCategory?.let { add(RowMenuItem(CategoryLabels.MOVE_TO, onClick = it)) }
    onAddCategoryAbove?.let { add(RowMenuItem(CategoryLabels.ADD_ABOVE, onClick = it)) }
    if (!isInbox) {
        add(RowMenuItem("Set as Inbox", onClick = onSetInbox))
        add(RowMenuItem("Archive", onClick = onArchive))
        add(RowMenuItem("Delete", destructive = true, onClick = onDelete))
    }
}

private fun LazyListScope.projectsSection(
    state: SettingsUiState,
    items: List<ProjectListItem>,
    categories: ProjectCategoryState,
    reorderState: ReorderableLazyListState,
    onDragStarted: (projectId: Long) -> Unit,
    onDragStopped: (projectId: Long) -> Unit,
    viewModel: SettingsViewModel,
    openDialog: (SettingsDialog) -> Unit,
    sharedRoutines: (Long) -> List<String>,
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

    if (items.isEmpty()) {
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

    // What a screen reader (which cannot drag) moves by: the ids of each level in display order,
    // within its category.
    val siblings = levelSiblings(items)
    val categoryIds = categories.categories.map { it.id }
    // "Add category above" follows the drawer's list (section 13.2), where the Inbox is not listed.
    val groups = ProjectCategories.layout(state.projects, state.inboxProjectId, categories)
    items(
        items,
        key = { item ->
            when (item) {
                is ProjectListItem.Header -> settingsCategoryKey(item.category.id)
                is ProjectListItem.Row -> settingsProjectKey(item.row.project.id)
            }
        },
        contentType = { if (it is ProjectListItem.Header) "category" else "project" },
    ) { item ->
        when (item) {
            is ProjectListItem.Header -> {
                val category = item.category
                val index = categoryIds.indexOf(category.id)
                ReorderableItem(reorderState, key = settingsCategoryKey(category.id)) {
                    CategoryHeader(
                        category = category,
                        count = item.count,
                        collapsed = false,
                        startPadding = 16.dp,
                        onToggle = null,
                        onRename = { openDialog(SettingsDialog.RenameCategory(category.id)) },
                        onMoveUp = if (index > 0) ({ viewModel.moveCategory(category.id, -1) }) else null,
                        onMoveDown = if (index in 0 until categoryIds.lastIndex) ({ viewModel.moveCategory(category.id, 1) }) else null,
                        onDelete = { viewModel.deleteCategory(category) },
                    )
                }
            }
            is ProjectListItem.Row -> {
                val row = item.row
                val project = row.project
                val isInbox = project.id == state.inboxProjectId
                val levelIds = siblings[project.id].orEmpty()
                val topLevel = ProjectCategories.isTopLevel(project, state.projects, state.inboxProjectId)
                LongPressReorderableRow(
                    reorderState = reorderState,
                    key = settingsProjectKey(project.id),
                    onDragStarted = { onDragStarted(project.id) },
                    onDragStopped = { onDragStopped(project.id) },
                ) { clickGuard ->
                    SettingsProjectRow(
                        project = project,
                        depth = row.depth,
                        isInbox = isInbox,
                        onEdit = clickGuard.guarded { openDialog(SettingsDialog.ProjectEditor(project.id)) },
                        menuItems = projectMenuItems(
                            project = project,
                            isInbox = isInbox,
                            onEdit = { openDialog(SettingsDialog.ProjectEditor(project.id)) },
                            onAddSubproject = { openDialog(SettingsDialog.SubprojectEditor(project.id)) },
                            onSetInbox = { viewModel.setInboxProject(project.id) },
                            onArchive = { openDialog(SettingsDialog.ArchiveProject(project.id)) },
                            onDelete = { deleteProject(project, state, viewModel, openDialog, sharedRoutines) },
                            onMoveToCategory = if (topLevel) ({ openDialog(SettingsDialog.MoveToCategory(project.id)) }) else null,
                            onAddCategoryAbove = if (topLevel && ProjectCategories.canSplitAt(groups, project.id)) {
                                { openDialog(SettingsDialog.AddCategoryAbove(project.id)) }
                            } else {
                                null
                            },
                        ),
                        modifier = Modifier.moveCustomActions(
                            onMoveUp = moveIdBy(levelIds, project.id, -1)
                                ?.let { order -> { viewModel.moveProject(project.id, item.categoryId, order) } },
                            onMoveDown = moveIdBy(levelIds, project.id, 1)
                                ?.let { order -> { viewModel.moveProject(project.id, item.categoryId, order) } },
                        ),
                    )
                }
            }
        }
    }

    item(key = "new_category") {
        TextButton(
            onClick = { openDialog(SettingsDialog.NewCategory) },
            modifier = Modifier.padding(start = 4.dp),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(CategoryLabels.NEW)
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
    sharedRoutines: (Long) -> List<String>,
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
                                deleteProject(project, state, viewModel, openDialog, sharedRoutines)
                            }),
                        )
                    }
                },
            )
        }
    }
}
