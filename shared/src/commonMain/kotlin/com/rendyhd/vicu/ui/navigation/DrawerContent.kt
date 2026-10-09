package com.rendyhd.vicu.ui.navigation

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.domain.model.BottomBarSlotType
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.ui.components.section.sectionStateDescription
import com.rendyhd.vicu.ui.components.shared.IconRegistry
import com.rendyhd.vicu.ui.components.shared.ProgressRing
import com.rendyhd.vicu.ui.components.shared.RollingCount
import com.rendyhd.vicu.ui.components.shared.SmartListIdentity
import com.rendyhd.vicu.ui.components.task.moveCustomActions
import com.rendyhd.vicu.ui.theme.LocalVicuColors
import com.rendyhd.vicu.util.ProjectProgress
import com.rendyhd.vicu.util.moveIdBy
import com.rendyhd.vicu.util.parseHexColor
import kotlinx.coroutines.flow.distinctUntilChanged
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState

/** How far each level of the project tree is moved in. */
private val ProjectIndent = 16.dp

/**
 * The navigation drawer: the smart lists, then the projects (a tree, any depth), the custom lists
 * and the labels. It is one lazy list, so only the rows that are on screen are composed however
 * many projects there are. Long-pressing a project, list or label drags it among the rows of its
 * own group (a project among its siblings); the drop is reported through the `onReorder` callbacks
 * with the ids of the group in their new order.
 *
 * A project row with tasks shows a [ProgressRing] from [projectProgress]. While [progressActive]
 * (the drawer is open) the projects whose rows are on screen are reported through
 * [onProgressRows], which is how the numbers are asked for: only for the rows that can be seen.
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
    progressActive: Boolean = false,
    onProgressRows: (Set<Long>) -> Unit = {},
    onCreateNewList: () -> Unit = {},
    onReorderProject: (movedId: Long, idsInNewOrder: List<Long>) -> Unit = { _, _ -> },
    onReorderList: (movedId: String, idsInNewOrder: List<String>) -> Unit = { _, _ -> },
    onReorderLabel: (movedId: Long, idsInNewOrder: List<Long>) -> Unit = { _, _ -> },
) {
    val haptic = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    val identity = LocalVicuColors.current.identity

    // While a row is dragged, its group is drawn in the order being made. At the drop the view
    // model takes over: it keeps the new order on show until the stored order says the same.
    var liveProjects by remember { mutableStateOf<List<ProjectRow>?>(null) }
    var liveLists by remember { mutableStateOf<List<CustomList>?>(null) }
    var liveLabels by remember { mutableStateOf<List<Label>?>(null) }
    val projectRows = liveProjects ?: state.projectRows
    val customLists = liveLists ?: state.customLists
    val labels = liveLabels ?: state.labels
    // What a screen reader (which cannot drag) moves by: the ids of each group in display order, and
    // for projects the ids of each level. A move is reported like the drop of a drag.
    val projectSiblings = remember(projectRows) { projectRows.groupBy({ it.parentId }, { it.project.id }) }
    val listIds = remember(customLists) { customLists.map { it.id } }
    val labelIds = remember(labels) { labels.map { it.id } }

    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val source = parseDrawerKey(from.key) ?: return@rememberReorderableLazyListState
        val target = parseDrawerKey(to.key) ?: return@rememberReorderableLazyListState
        if (source.group != target.group) return@rememberReorderableLazyListState
        val moved = when (source.group) {
            DrawerGroup.PROJECT -> {
                val sourceId = source.id.toLongOrNull() ?: return@rememberReorderableLazyListState
                val targetId = target.id.toLongOrNull() ?: return@rememberReorderableLazyListState
                moveProjectRow(liveProjects ?: state.projectRows, sourceId, targetId)
                    ?.also { liveProjects = it } != null
            }
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
                // Displaced smart lists (removed from bottom bar)
                val displaced = state.displacedSmartLists
                if (displaced.isNotEmpty()) {
                    item(key = "smart_spacer", contentType = "spacer") {
                        Spacer(Modifier.height(12.dp))
                    }
                    if (BottomBarSlotType.TODAY in displaced) {
                        item(key = "smart_today", contentType = "smart") {
                            SmartListItem(
                                label = "Today",
                                icon = SmartListIdentity.TODAY.icon,
                                iconTint = SmartListIdentity.TODAY.color(identity),
                                selected = currentRoute == "TodayRoute",
                                onClick = { onNavigate(TodayRoute) },
                            )
                        }
                    }
                    if (BottomBarSlotType.UPCOMING in displaced) {
                        item(key = "smart_upcoming", contentType = "smart") {
                            SmartListItem(
                                label = "Upcoming",
                                icon = SmartListIdentity.UPCOMING.icon,
                                iconTint = SmartListIdentity.UPCOMING.color(identity),
                                selected = currentRoute == "UpcomingRoute",
                                onClick = { onNavigate(UpcomingRoute) },
                            )
                        }
                    }
                    if (BottomBarSlotType.ANYTIME in displaced) {
                        item(key = "smart_anytime", contentType = "smart") {
                            SmartListItem(
                                label = "Anytime",
                                icon = SmartListIdentity.ANYTIME.icon,
                                iconTint = SmartListIdentity.ANYTIME.color(identity),
                                selected = currentRoute == "AnytimeRoute",
                                onClick = { onNavigate(AnytimeRoute) },
                            )
                        }
                    }
                }

                // Logbook
                item(key = "smart_logbook", contentType = "smart") {
                    if (displaced.isEmpty()) Spacer(Modifier.height(12.dp))
                    SmartListItem(
                        label = "Logbook",
                        icon = SmartListIdentity.LOGBOOK.icon,
                        iconTint = SmartListIdentity.LOGBOOK.color(identity),
                        selected = currentRoute == "LogbookRoute",
                        onClick = { onNavigate(LogbookRoute) },
                    )
                }

                // Routines; hidden when they are turned off in Settings
                if (state.routinesEnabled) {
                    item(key = "smart_routines", contentType = "smart") {
                        SmartListItem(
                            label = "Routines",
                            icon = SmartListIdentity.ROUTINES.icon,
                            iconTint = SmartListIdentity.ROUTINES.color(identity),
                            selected = currentRoute == "RoutinesRoute",
                            onClick = { onNavigate(RoutinesRoute) },
                        )
                    }
                }

                // Review (with overdue badge); hidden when the feature is disabled
                if (state.reviewEnabled) {
                    item(key = "smart_review", contentType = "smart") {
                        NavigationDrawerItem(
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
                    }
                }

                // Projects section
                item(key = "divider_projects", contentType = "divider") {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
                item(key = "header_projects", contentType = "header") {
                    SectionHeader(
                        title = "Projects",
                        expanded = state.projectsExpanded,
                        onToggle = onToggleProjects,
                    )
                }
                if (state.projectsExpanded) {
                    items(
                        projectRows,
                        key = { drawerKey(DrawerGroup.PROJECT, it.project.id) },
                        contentType = { "project" },
                    ) { row ->
                        val projectId = row.project.id
                        val siblings = projectSiblings[row.parentId].orEmpty()
                        DrawerReorderableRow(
                            reorderState = reorderState,
                            key = drawerKey(DrawerGroup.PROJECT, projectId),
                            onDragStopped = {
                                liveProjects?.let { onReorderProject(projectId, siblingIds(it, projectId)) }
                                liveProjects = null
                            },
                        ) {
                            ProjectItem(
                                row = row,
                                progress = projectProgress[projectId],
                                selected = currentRoute == "ProjectRoute/$projectId",
                                onClick = { onNavigate(ProjectRoute(projectId)) },
                                onToggleCollapsed = { onToggleProjectCollapsed(projectId) },
                                onMoveUp = moveIdBy(siblings, projectId, -1)
                                    ?.let { order -> { onReorderProject(projectId, order) } },
                                onMoveDown = moveIdBy(siblings, projectId, 1)
                                    ?.let { order -> { onReorderProject(projectId, order) } },
                            )
                        }
                    }
                }

                // Custom Lists section
                item(key = "divider_lists", contentType = "divider") {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
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
                        DrawerReorderableRow(
                            reorderState = reorderState,
                            key = drawerKey(DrawerGroup.LIST, list.id),
                            onDragStopped = {
                                liveLists?.let { onReorderList(list.id, it.map { moved -> moved.id }) }
                                liveLists = null
                            },
                        ) {
                            NavigationDrawerItem(
                                label = { Text(list.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                icon = {
                                    Icon(
                                        IconRegistry.PRESET_ICONS.firstOrNull { it.key == list.icon }?.icon
                                            ?: Icons.Outlined.FilterList,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                selected = currentRoute == "CustomListRoute/${list.id}",
                                onClick = { onNavigate(CustomListRoute(list.id)) },
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

                // Labels section
                if (state.labels.isNotEmpty()) {
                    item(key = "divider_tags", contentType = "divider") {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }
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
                            DrawerReorderableRow(
                                reorderState = reorderState,
                                key = drawerKey(DrawerGroup.LABEL, label.id),
                                onDragStopped = {
                                    liveLabels?.let { onReorderLabel(label.id, it.map { moved -> moved.id }) }
                                    liveLabels = null
                                },
                            ) {
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
                                    onClick = { onNavigate(TagRoute(label.id)) },
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
}

/**
 * A row of the drawer that can be dragged among the rows of its own group: a long press picks it
 * up, [onDragStopped] fires when it is let go. The Surface stays in the tree when idle, so picking
 * the row up does not reset what is inside it.
 */
@Composable
private fun LazyItemScope.DrawerReorderableRow(
    reorderState: ReorderableLazyListState,
    key: String,
    onDragStopped: () -> Unit,
    content: @Composable () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    ReorderableItem(reorderState, key = key) { isDragging ->
        val elevation by animateDpAsState(
            if (isDragging) 4.dp else 0.dp,
            label = "dragElevation",
        )
        Surface(
            modifier = Modifier.longPressDraggableHandle(
                onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                onDragStopped = onDragStopped,
            ),
            shadowElevation = elevation,
            color = if (isDragging) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                Color.Transparent
            },
        ) {
            content()
        }
    }
}

/** A project of the tree: indented by its depth, with a button to open or close what is below it. */
@Composable
private fun ProjectItem(
    row: ProjectRow,
    progress: ProjectProgress?,
    selected: Boolean,
    onClick: () -> Unit,
    onToggleCollapsed: () -> Unit,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    val project = row.project
    val projectColor = parseHexColor(project.hexColor)
    // The badge slot is the trailing end of the row: the progress ring of a project with tasks,
    // then the open/close button of a project with children.
    val badge: (@Composable () -> Unit)? = if (row.hasChildren || progress != null) {
        {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (progress != null) {
                    ProgressRing(
                        progress = progress,
                        color = projectColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (row.hasChildren) {
                    IconButton(onClick = onToggleCollapsed) {
                        Icon(
                            imageVector = if (row.expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = projectToggleDescription(project.title, row.expanded),
                        )
                    }
                } else if (progress != null) {
                    // The room of the open/close button, so the rings of an area row and of its
                    // projects are one column.
                    Spacer(Modifier.width(48.dp))
                }
            }
        }
    } else {
        null
    }
    NavigationDrawerItem(
        label = { Text(project.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        icon = {
            Icon(
                Icons.Outlined.Folder,
                contentDescription = null,
                tint = projectColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
 * high) and announces whether the section is open; the chevron is only a picture of that.
 */
@Composable
private fun SectionHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
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
        Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
