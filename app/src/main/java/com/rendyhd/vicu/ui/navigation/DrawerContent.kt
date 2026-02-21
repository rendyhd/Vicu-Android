package com.rendyhd.vicu.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val SmartListLogbookColor = Color(0xFF16A34A)

@Composable
fun DrawerContent(
    state: DrawerUiState,
    currentRoute: String?,
    onNavigate: (Any) -> Unit,
    onToggleProjects: () -> Unit,
    onToggleLists: () -> Unit,
    onToggleTags: () -> Unit,
    onCreateNewList: () -> Unit = {},
) {
    ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
        Column(modifier = Modifier.fillMaxHeight()) {
            LazyColumn(
                modifier = Modifier.weight(1f),
            ) {
                // Logbook (not in bottom bar)
                item(key = "smart_logbook") {
                    Spacer(Modifier.height(12.dp))
                    SmartListItem(
                        label = "Logbook",
                        icon = Icons.Outlined.CheckCircle,
                        iconTint = SmartListLogbookColor,
                        selected = currentRoute == "LogbookRoute",
                        onClick = { onNavigate(LogbookRoute) },
                    )
                }

                // Projects section
                item(key = "divider_projects") {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
                item(key = "header_projects") {
                    SectionHeader(
                        title = "PROJECTS",
                        expanded = state.projectsExpanded,
                        onToggle = onToggleProjects,
                    )
                }
                if (state.projectsExpanded) {
                    state.projectTree.forEach { node ->
                        item(key = "project_${node.project.id}") {
                            ProjectItem(
                                project = node.project,
                                selected = currentRoute == "ProjectRoute/${node.project.id}",
                                onClick = { onNavigate(ProjectRoute(node.project.id)) },
                            )
                        }
                    }
                }

                // Custom Lists section
                item(key = "divider_lists") {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
                item(key = "header_lists") {
                    SectionHeader(
                        title = "LISTS",
                        expanded = state.listsExpanded,
                        onToggle = onToggleLists,
                    )
                }
                if (state.listsExpanded) {
                    items(state.customLists, key = { "list_${it.id}" }) { list ->
                        NavigationDrawerItem(
                            label = { Text(list.name) },
                            icon = {
                                Icon(
                                    Icons.Outlined.FilterList,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            selected = currentRoute == "CustomListRoute/${list.id}",
                            onClick = { onNavigate(CustomListRoute(list.id)) },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                        )
                    }
                    item(key = "new_list") {
                        NavigationDrawerItem(
                            label = {
                                Text(
                                    "New List",
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            },
                            icon = {
                                Icon(
                                    Icons.Outlined.Add,
                                    contentDescription = "New List",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            selected = false,
                            onClick = onCreateNewList,
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                        )
                    }
                }

                // Tags section
                if (state.labels.isNotEmpty()) {
                    item(key = "divider_tags") {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }
                    item(key = "header_tags") {
                        SectionHeader(
                            title = "TAGS",
                            expanded = state.tagsExpanded,
                            onToggle = onToggleTags,
                        )
                    }
                    if (state.tagsExpanded) {
                        items(state.labels, key = { "tag_${it.id}" }) { label ->
                            val dotColor = parseHexColor(label.hexColor)
                            NavigationDrawerItem(
                                label = { Text(label.title) },
                                icon = {
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .clip(CircleShape)
                                            .background(
                                                dotColor
                                                    ?: MaterialTheme.colorScheme.onSurfaceVariant,
                                            ),
                                    )
                                },
                                selected = currentRoute == "TagRoute/${label.id}",
                                onClick = { onNavigate(TagRoute(label.id)) },
                                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                            )
                        }
                    }
                }

                // Settings
                item(key = "divider_settings") {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
                item(key = "settings") {
                    NavigationDrawerItem(
                        label = { Text("Settings") },
                        icon = {
                            Icon(
                                Icons.Outlined.Settings,
                                contentDescription = "Settings",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        selected = currentRoute == "SettingsRoute",
                        onClick = { onNavigate(SettingsRoute) },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    )
                }

                item(key = "bottom_spacer") {
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(8.dp))
        }
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
        icon = { Icon(icon, contentDescription = label, tint = iconTint) },
        selected = selected,
        onClick = onClick,
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
    )
}

@Composable
private fun SectionHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 28.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ProjectItem(
    project: com.rendyhd.vicu.domain.model.Project,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val folderColor = parseHexColor(project.hexColor)
    NavigationDrawerItem(
        label = { Text(project.title) },
        icon = {
            Icon(
                Icons.Outlined.Folder,
                contentDescription = null,
                tint = folderColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        selected = selected,
        onClick = onClick,
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
    )
}

private fun parseHexColor(hex: String): Color? {
    if (hex.isBlank()) return null
    return try {
        val normalized = if (hex.startsWith("#")) hex else "#$hex"
        Color(android.graphics.Color.parseColor(normalized))
    } catch (_: Exception) {
        null
    }
}
