package com.rendyhd.vicu.ui.navigation

import com.rendyhd.vicu.ui.components.pro.ProIcons
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.data.local.DrawerView
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.ui.components.category.CategoryHeader
import com.rendyhd.vicu.ui.components.category.CategoryLabels
import com.rendyhd.vicu.ui.components.category.CategoryNameDialog
import com.rendyhd.vicu.ui.components.section.sectionStateDescription
import com.rendyhd.vicu.ui.components.shared.DragClickGuard
import com.rendyhd.vicu.ui.components.shared.IconRegistry
import com.rendyhd.vicu.ui.components.shared.ProgressRing
import com.rendyhd.vicu.ui.components.shared.RollingCount
import com.rendyhd.vicu.ui.components.shared.SmartListIdentity
import com.rendyhd.vicu.ui.components.shared.dragClickGuardPresses
import com.rendyhd.vicu.ui.components.task.moveCustomActions
import com.rendyhd.vicu.ui.theme.LocalVicuColors
import com.rendyhd.vicu.util.countOf
import com.rendyhd.vicu.util.ProjectCategory
import com.rendyhd.vicu.util.ProjectProgress
import com.rendyhd.vicu.util.moveIdBy
import com.rendyhd.vicu.util.parseHexColor
import kotlinx.coroutines.flow.distinctUntilChanged
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState

/** How far each level of the project tree is moved in. */
private val ProjectIndent = 16.dp

/** The width of the open task count column (three digits); see ProjectItem. */
private val CountColumnWidth = 28.dp

/**
 * The navigation drawer: the smart lists, then the projects (a tree, any depth, under the headers
 * of the user's categories when there are any), the custom lists and the labels. It is one lazy
 * list, so only the rows that are on screen are composed however many projects there are.
 * Long-pressing a project, list or label drags it among the rows of its own group (a project among
 * its siblings; a top-level project also past a category's header, into that category); the drop is
 * reported through [onMoveProject] and the `onReorder` callbacks with the ids in their new order.
 * A category's header opens and closes it; a long press offers Rename, Move up, Move down and Delete.
 *
 * A project row with tasks shows a [ProgressRing] from [projectProgress]. While [progressActive]
 * (the drawer is open) the projects whose rows are on screen are reported through
 * [onProgressRows], which is how the numbers are asked for: only for the rows that can be seen.
 * With [projectOpenCounts] (Settings, "Show task count") a project also shows how many open tasks
 * it has; either, both or neither can be on.
 */
@Composable
fun DrawerContent(
    state: DrawerUiState,
    currentRoute: String?,
    onNavigate: (Any) -> Unit,
    onToggleProjects: () -> Unit,
    onToggleLists: () -> Unit,
    onToggleTags: () -> Unit,
    onToggleProjectCollapsed: (projectId: Long) -> Unit = {},
    projectProgress: Map<Long, ProjectProgress> = emptyMap(),
    projectOpenCounts: Map<Long, Int> = emptyMap(),
    progressActive: Boolean = false,
    onProgressRows: (Set<Long>) -> Unit = {},
    onCreateNewList: () -> Unit = {},
    onCreateProject: () -> Unit = {},
    /**
     * A project was dropped: it is now in the group of the category (null: none; it only counts for
     * a top-level project) among the ids of its level there, in their new order.
     */
    onMoveProject: (movedId: Long, categoryId: String?, idsInNewOrder: List<Long>) -> Unit = { _, _, _ -> },
    onToggleCategoryCollapsed: (categoryId: String) -> Unit = {},
    onRenameCategory: (categoryId: String, name: String) -> Unit = { _, _ -> },
    onMoveCategory: (categoryId: String, by: Int) -> Unit = { _, _ -> },
    onDeleteCategory: (ProjectCategory) -> Unit = {},
    onReorderList: (movedId: String, idsInNewOrder: List<String>) -> Unit = { _, _ -> },
    onReorderLabel: (movedId: Long, idsInNewOrder: List<Long>) -> Unit = { _, _ -> },
    /** The assistant's Ask is set up and on (docs/ai-assistant-v1.md, section 5). */
    showAsk: Boolean = false,
) {
    val haptic = LocalHapticFeedback.current
    val listState = rememberLazyListState()

    // While a row is dragged, its group is drawn in the order being made. At the drop the view
    // model takes over: it keeps the new order on show until the stored order says the same.
    var liveProjects by remember { mutableStateOf<List<ProjectListItem>?>(null) }
    var liveLists by remember { mutableStateOf<List<CustomList>?>(null) }
    var liveLabels by remember { mutableStateOf<List<Label>?>(null) }
    var renaming by remember { mutableStateOf<ProjectCategory?>(null) }
    val projectItems = liveProjects ?: state.projectItems
    val customLists = liveLists ?: state.customLists
    val labels = liveLabels ?: state.labels
    // What a screen reader (which cannot drag) moves by: the ids of each group in display order, and
    // for projects the ids of each level within its category. A move is reported like the drop of a drag.
    val projectSiblings = remember(projectItems) { levelSiblings(projectItems) }
    val categoryIds = remember(state.categories) { state.categories.categories.map { it.id } }
    val listIds = remember(customLists) { customLists.map { it.id } }
    val labelIds = remember(labels) { labels.map { it.id } }

    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val source = parseDrawerKey(from.key) ?: return@rememberReorderableLazyListState
        val target = parseDrawerKey(to.key) ?: return@rememberReorderableLazyListState
        // A project goes over projects and category headers; the others stay in their own group.
        val sameGroup = source.group == target.group || (source.group == DrawerGroup.PROJECT && target.group == DrawerGroup.CATEGORY)
        if (!sameGroup) return@rememberReorderableLazyListState
        val moved = when (source.group) {
            DrawerGroup.PROJECT -> {
                val sourceId = source.id.toLongOrNull() ?: return@rememberReorderableLazyListState
                val current = liveProjects ?: state.projectItems
                val targetIndex = if (target.group == DrawerGroup.CATEGORY) {
                    current.indexOfCategory(target.id)
                } else {
                    target.id.toLongOrNull()?.let { current.indexOfProject(it) } ?: return@rememberReorderableLazyListState
                }
                moveProjectItem(current, sourceId, targetIndex)?.also { liveProjects = it } != null
            }
            DrawerGroup.CATEGORY -> false
            DrawerGroup.LIST ->
                moveItem(liveLists ?: state.customLists, source.id, target.id) { it.id }
                    ?.also { liveLists = it } != null
            DrawerGroup.LABEL ->
                moveItem(liveLabels ?: state.labels, source.id, target.id) { it.id.toString() }
                    ?.also { liveLabels = it } != null
        }
        if (moved) haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) // a reorder step (design-system-v1, haptics)
    }

    // The projects with a row on screen, reported while the drawer is open and as the list scrolls.
    LaunchedEffect(progressActive, listState) {
        if (!progressActive) {
            onProgressRows(emptySet())
            return@LaunchedEffect
        }
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.mapNotNullTo(HashSet()) { item ->
                parseDrawerKey(item.key)?.takeIf { it.group == DrawerGroup.PROJECT }?.id?.toLongOrNull()
            }
        }.distinctUntilChanged().collect { onProgressRows(it) }
    }

    ModalDrawerSheet {
        Column(modifier = Modifier.fillMaxHeight()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
            ) {
                // What the drawer lists, in the user's order (Settings, General, Drawer): the views, with
                // the projects, the lists and the labels as blocks among them.
                fun projectsBlock() {
                    item(key = "header_projects", contentType = "header") {
                        SectionHeader(
                            title = "Projects",
                            expanded = state.projectsExpanded,
                            onToggle = onToggleProjects,
                            onAdd = onCreateProject,
                            addDescription = "New project",
                        )
                    }
                    if (state.projectsExpanded) {
                        items(
                            projectItems,
                            key = { item ->
                                when (item) {
                                    is ProjectListItem.Header -> drawerKey(DrawerGroup.CATEGORY, item.category.id)
                                    is ProjectListItem.Row -> drawerKey(DrawerGroup.PROJECT, item.row.project.id)
                                }
                            },
                            contentType = { if (it is ProjectListItem.Header) "category" else "project" },
                        ) { item ->
                            when (item) {
                                is ProjectListItem.Header -> {
                                    val category = item.category
                                    val index = categoryIds.indexOf(category.id)
                                    // A target of the drag, never dragged itself: it has no drag handle.
                                    ReorderableItem(reorderState, key = drawerKey(DrawerGroup.CATEGORY, category.id)) {
                                        CategoryHeader(
                                            category = category,
                                            count = item.count,
                                            collapsed = item.collapsed,
                                            startPadding = 28.dp,
                                            onToggle = { onToggleCategoryCollapsed(category.id) },
                                            onRename = { renaming = category },
                                            onMoveUp = if (index > 0) ({ onMoveCategory(category.id, -1) }) else null,
                                            onMoveDown = if (index in 0 until categoryIds.lastIndex) ({ onMoveCategory(category.id, 1) }) else null,
                                            onDelete = { onDeleteCategory(category) },
                                        )
                                    }
                                }
                                is ProjectListItem.Row -> {
                                    val row = item.row
                                    val projectId = row.project.id
                                    val siblings = projectSiblings[projectId].orEmpty()
                                    LongPressReorderableRow(
                                        reorderState = reorderState,
                                        key = drawerKey(DrawerGroup.PROJECT, projectId),
                                        // Carried as one row: its open subprojects would be left behind, rows to pass.
                                        onDragStarted = { liveProjects = (liveProjects ?: state.projectItems).withoutDescendants(projectId) },
                                        onDragStopped = {
                                            liveProjects?.let { items ->
                                                droppedPlace(items, projectId)?.let { (categoryId, ids) -> onMoveProject(projectId, categoryId, ids) }
                                            }
                                            liveProjects = null
                                        },
                                    ) { clickGuard ->
                                        ProjectItem(
                                            row = row,
                                            progress = projectProgress[projectId],
                                            openCount = projectOpenCounts[projectId],
                                            countColumn = projectOpenCounts.isNotEmpty(),
                                            selected = currentRoute == "ProjectRoute/$projectId",
                                            onClick = clickGuard.guarded { onNavigate(ProjectRoute(projectId)) },
                                            onToggleCollapsed = clickGuard.guarded { onToggleProjectCollapsed(projectId) },
                                            onMoveUp = moveIdBy(siblings, projectId, -1)
                                                ?.let { order -> { onMoveProject(projectId, item.categoryId, order) } },
                                            onMoveDown = moveIdBy(siblings, projectId, 1)
                                                ?.let { order -> { onMoveProject(projectId, item.categoryId, order) } },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                fun listsBlock() {
                    item(key = "header_lists", contentType = "header") {
                        SectionHeader(
                            title = "Lists",
                            expanded = state.listsExpanded,
                            onToggle = onToggleLists,
                        )
                    }
                    if (state.listsExpanded) {
                        items(
                            customLists,
                            key = { drawerKey(DrawerGroup.LIST, it.id) },
                            contentType = { "list" },
                        ) { list ->
                            LongPressReorderableRow(
                                reorderState = reorderState,
                                key = drawerKey(DrawerGroup.LIST, list.id),
                                onDragStopped = {
                                    liveLists?.let { onReorderList(list.id, it.map { moved -> moved.id }) }
                                    liveLists = null
                                },
                            ) { clickGuard ->
                                NavigationDrawerItem(
                                    label = { Text(list.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    // None leaves the icon's room to the name (a null slot has no gap either).
                                    icon = IconRegistry.customListIcon(list.icon)?.let { vector ->
                                        {
                                            Icon(
                                                vector,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    selected = currentRoute == "CustomListRoute/${list.id}",
                                    onClick = clickGuard.guarded { onNavigate(CustomListRoute(list.id)) },
                                    modifier = Modifier
                                        .padding(NavigationDrawerItemDefaults.ItemPadding)
                                        .moveCustomActions(
                                            onMoveUp = moveIdBy(listIds, list.id, -1)
                                                ?.let { order -> { onReorderList(list.id, order) } },
                                            onMoveDown = moveIdBy(listIds, list.id, 1)
                                                ?.let { order -> { onReorderList(list.id, order) } },
                                        ),
                                )
                            }
                        }
                        item(key = "new_list", contentType = "action") {
                            NavigationDrawerItem(
                                label = {
                                    Text(
                                        "New List",
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                // Decorative: the label next to it already says "New List".
                                icon = {
                                    Icon(
                                        Icons.Outlined.Add,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                selected = false,
                                onClick = onCreateNewList,
                                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                            )
                        }
                    }
                }

                fun labelsBlock() {
                    item(key = "header_tags", contentType = "header") {
                        SectionHeader(
                            title = "Labels",
                            expanded = state.tagsExpanded,
                            onToggle = onToggleTags,
                        )
                    }
                    if (state.tagsExpanded) {
                        items(
                            labels,
                            key = { drawerKey(DrawerGroup.LABEL, it.id) },
                            contentType = { "label" },
                        ) { label ->
                            LongPressReorderableRow(
                                reorderState = reorderState,
                                key = drawerKey(DrawerGroup.LABEL, label.id),
                                onDragStopped = {
                                    liveLabels?.let { onReorderLabel(label.id, it.map { moved -> moved.id }) }
                                    liveLabels = null
                                },
                            ) { clickGuard ->
                                NavigationDrawerItem(
                                    label = { Text(label.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    icon = {
                                        Box(
                                            modifier = Modifier
                                                .size(12.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    parseHexColor(label.hexColor)
                                                        ?: MaterialTheme.colorScheme.onSurfaceVariant,
                                                ),
                                        )
                                    },
                                    selected = currentRoute == "TagRoute/${label.id}",
                                    onClick = clickGuard.guarded { onNavigate(TagRoute(label.id)) },
                                    modifier = Modifier
                                        .padding(NavigationDrawerItemDefaults.ItemPadding)
                                        .moveCustomActions(
                                            onMoveUp = moveIdBy(labelIds, label.id, -1)
                                                ?.let { order -> { onReorderLabel(label.id, order) } },
                                            onMoveDown = moveIdBy(labelIds, label.id, 1)
                                                ?.let { order -> { onReorderLabel(label.id, order) } },
                                        ),
                                )
                            }
                        }
                    }
                }

                // The labels have no block to draw while there are none.
                val entries = state.entries(askReady = showAsk)
                    .filter { it != DrawerEntry.Block(DrawerBlock.LABELS) || state.labels.isNotEmpty() }
                item(key = "smart_spacer", contentType = "spacer") {
                    Spacer(Modifier.height(12.dp))
                }
                entries.forEachIndexed { index, entry ->
                    // A block starts with its header, so a rule sets it off from what is above it,
                    // and the views under a block from that block.
                    val previous = entries.getOrNull(index - 1)
                    if (previous != null && (entry is DrawerEntry.Block || previous is DrawerEntry.Block)) {
                        item(key = "divider_${entry.key.lowercase()}", contentType = "divider") {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        }
                    }
                    when (entry) {
                        is DrawerEntry.View -> item(key = "smart_${entry.view.name.lowercase()}", contentType = "smart") {
                            DrawerViewItem(entry.view, state, currentRoute, onNavigate)
                        }
                        is DrawerEntry.Block -> when (entry.block) {
                            DrawerBlock.PROJECTS -> projectsBlock()
                            DrawerBlock.LISTS -> listsBlock()
                            DrawerBlock.LABELS -> labelsBlock()
                        }
                    }
                }

                // Settings
                item(key = "divider_settings", contentType = "divider") {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
                item(key = "settings", contentType = "smart") {
                    NavigationDrawerItem(
                        label = { Text("Settings") },
                        icon = {
                            Icon(
                                Icons.Outlined.Settings,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        selected = currentRoute == "SettingsRoute",
                        onClick = { onNavigate(SettingsRoute) },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    )
                }

                item(key = "bottom_spacer", contentType = "spacer") {
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    renaming?.let { category ->
        CategoryNameDialog(
            title = CategoryLabels.RENAME_TITLE,
            confirmLabel = "Save",
            initialName = category.name,
            onConfirm = { name ->
                onRenameCategory(category.id, name)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
}

/**
 * A row that can be dragged among the rows of its own group (the drawer's, the Projects tab of
 * Settings): a long press picks it up, [onDragStopped] fires when it is let go. The Surface stays
 * in the tree when idle, so picking the row up does not reset what is inside it. [content] wraps
 * its clicks in the [DragClickGuard] it is given, so letting go of a row that was picked up does
 * not also open it.
 */
@Composable
internal fun LazyItemScope.LongPressReorderableRow(
    reorderState: ReorderableLazyListState,
    key: String,
    onDragStopped: () -> Unit,
    onDragStarted: () -> Unit = {},
    content: @Composable (clickGuard: DragClickGuard) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val clickGuard = remember { DragClickGuard() }
    ReorderableItem(reorderState, key = key) { isDragging ->
        val elevation by animateDpAsState(
            if (isDragging) 4.dp else 0.dp,
            label = "dragElevation",
        )
        Surface(
            modifier = Modifier
                .dragClickGuardPresses(clickGuard)
                .longPressDraggableHandle(
                    onDragStarted = {
                        clickGuard.dragStarted()
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDragStarted()
                    },
                    onDragStopped = onDragStopped,
                ),
            shadowElevation = elevation,
            color = if (isDragging) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                Color.Transparent
            },
        ) {
            content(clickGuard)
        }
    }
}

/** A project of the tree: indented by its depth, with a button to open or close what is below it. */
@Composable
private fun ProjectItem(
    row: ProjectRow,
    progress: ProjectProgress?,
    openCount: Int?,
    countColumn: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onToggleCollapsed: () -> Unit,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    val project = row.project
    val projectColor = parseHexColor(project.hexColor)
    // The badge slot is the trailing end of the row: the progress ring of a project with tasks,
    // its open tasks while counts are shown ([countColumn]: one end-aligned column, so the numbers
    // and the rings line up row after row), then the open/close button of a project with children.
    val badge: (@Composable () -> Unit)? = if (row.hasChildren || progress != null || openCount != null) {
        {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (progress != null) {
                    ProgressRing(
                        progress = progress,
                        color = projectColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (countColumn) {
                    Text(
                        text = openCount?.toString().orEmpty(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .widthIn(min = CountColumnWidth)
                            .semantics { if (openCount != null) contentDescription = countOf(openCount, "open task") },
                    )
                }
                if (row.hasChildren) {
                    IconButton(onClick = onToggleCollapsed) {
                        Icon(
                            imageVector = if (row.expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = projectToggleDescription(project.title, row.expanded),
                        )
                    }
                } else {
                    // The room of the open/close button, so the rings and counts of an area row and
                    // of its projects are one column.
                    Spacer(Modifier.width(48.dp))
                }
            }
        }
    } else {
        null
    }
    NavigationDrawerItem(
        label = { Text(project.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        // A project without an icon starts its name where the folder would be (cross-app semantics 11.3).
        icon = if (project.showIcon) {
            {
                Icon(
                    Icons.Outlined.Folder,
                    contentDescription = null,
                    tint = projectColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            null
        },
        badge = badge,
        selected = selected,
        onClick = onClick,
        modifier = Modifier
            .padding(NavigationDrawerItemDefaults.ItemPadding)
            .padding(start = ProjectIndent * projectIndentLevel(row.depth))
            .moveCustomActions(onMoveUp, onMoveDown),
    )
}

/** The row of [view] in the drawer; Review carries its badge of projects due. */
@Composable
private fun DrawerViewItem(view: DrawerView, state: DrawerUiState, currentRoute: String?, onNavigate: (Any) -> Unit) {
    val identity = LocalVicuColors.current.identity
    when (view) {
        DrawerView.INBOX -> SmartListItem(
            label = "Inbox",
            icon = SmartListIdentity.INBOX.icon,
            iconTint = SmartListIdentity.INBOX.color(identity),
            selected = currentRoute == "InboxRoute",
            onClick = { onNavigate(InboxRoute) },
        )
        DrawerView.TODAY -> SmartListItem(
            label = "Today",
            icon = SmartListIdentity.TODAY.icon,
            iconTint = SmartListIdentity.TODAY.color(identity),
            selected = currentRoute == "TodayRoute",
            onClick = { onNavigate(TodayRoute) },
        )
        DrawerView.PLAN -> SmartListItem(
            label = "Plan",
            icon = SmartListIdentity.PLAN.icon,
            iconTint = SmartListIdentity.PLAN.color(identity),
            selected = currentRoute == "PlanRoute",
            onClick = { onNavigate(PlanRoute) },
        )
        DrawerView.UPCOMING -> SmartListItem(
            label = "Upcoming",
            icon = SmartListIdentity.UPCOMING.icon,
            iconTint = SmartListIdentity.UPCOMING.color(identity),
            selected = currentRoute == "UpcomingRoute",
            onClick = { onNavigate(UpcomingRoute) },
        )
        DrawerView.ANYTIME -> SmartListItem(
            label = "Anytime",
            icon = SmartListIdentity.ANYTIME.icon,
            iconTint = SmartListIdentity.ANYTIME.color(identity),
            selected = currentRoute == "AnytimeRoute",
            onClick = { onNavigate(AnytimeRoute) },
        )
        DrawerView.LOGBOOK -> SmartListItem(
            label = "Logbook",
            icon = SmartListIdentity.LOGBOOK.icon,
            iconTint = SmartListIdentity.LOGBOOK.color(identity),
            selected = currentRoute == "LogbookRoute",
            onClick = { onNavigate(LogbookRoute) },
        )
        DrawerView.ROUTINES -> SmartListItem(
            label = "Routines",
            icon = SmartListIdentity.ROUTINES.icon,
            iconTint = SmartListIdentity.ROUTINES.color(identity),
            selected = currentRoute == "RoutinesRoute",
            onClick = { onNavigate(RoutinesRoute) },
        )
        // With the overdue badge.
        DrawerView.REVIEW -> NavigationDrawerItem(
            label = { Text("Review") },
            icon = {
                Icon(
                    SmartListIdentity.REVIEW.icon,
                    contentDescription = null,
                    tint = SmartListIdentity.REVIEW.color(identity),
                )
            },
            badge = {
                if (state.reviewOverdueCount > 0) {
                    // Rolls by one when a review is done or one falls due (card 4.11b).
                    RollingCount(
                        value = state.reviewOverdueCount,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            },
            selected = currentRoute == "ReviewRoute",
            onClick = { onNavigate(ReviewRoute) },
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        )
        DrawerView.FLAGGED -> SmartListItem(
            label = "Flagged",
            icon = ProIcons.FLAGGED,
            iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
            selected = currentRoute == "FlaggedRoute",
            onClick = { onNavigate(FlaggedRoute) },
        )
        DrawerView.WAITING_ON -> SmartListItem(
            label = "Waiting on",
            icon = ProIcons.WAITING_ON,
            iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
            selected = currentRoute == "WaitingOnRoute",
            onClick = { onNavigate(WaitingOnRoute) },
        )
        DrawerView.PEOPLE -> SmartListItem(
            label = "People",
            icon = ProIcons.PEOPLE,
            iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
            selected = currentRoute == "PeopleRoute",
            onClick = { onNavigate(PeopleRoute) },
        )
        // With the smart lists: with many projects it got lost at the bottom.
        DrawerView.ASK -> NavigationDrawerItem(
            label = { Text("Ask") },
            icon = {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            selected = currentRoute == "AskRoute",
            onClick = { onNavigate(AskRoute) },
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        )
    }
}

@Composable
private fun SmartListItem(
    label: String,
    icon: ImageVector,
    iconTint: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        label = { Text(label) },
        // Decorative: the label next to it already names the list, so a screen reader says it once.
        icon = { Icon(icon, contentDescription = null, tint = iconTint) },
        selected = selected,
        onClick = onClick,
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
    )
}

/**
 * A section title that opens and closes its section. The whole row is the button (at least 48 dp
 * high) and announces whether the section is open; the chevron is only a picture of that. With
 * [onAdd] a + button ([addDescription] for a screen reader) sits before the chevron.
 */
@Composable
private fun SectionHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    onAdd: (() -> Unit)? = null,
    addDescription: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = if (expanded) "Collapse" else "Expand",
                role = Role.Button,
                onClick = onToggle,
            )
            .semantics {
                // The visible title is upper case, which a screen reader may spell out.
                contentDescription = title
                stateDescription = sectionStateDescription(expanded)
            }
            .heightIn(min = 48.dp)
            .padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (onAdd != null) {
            IconButton(onClick = onAdd) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = addDescription,
                    // The accent of the drawer's other "new" action, the New List row.
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
        }
        Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
