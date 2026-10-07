package com.rendyhd.vicu.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.auth.AuthDebugLog
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.CustomListSyncStatus
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.util.BuildInfo
import com.rendyhd.vicu.util.buildProjectTree

internal fun LazyListScope.projectsSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    openDialog: (SettingsDialog) -> Unit,
    showArchivedProjects: Boolean,
    onShowArchivedProjectsChange: (Boolean) -> Unit,
) {
    fun deleteProject(project: Project) {
        if (state.behaviorPrefs.confirmBeforeDelete) {
            openDialog(SettingsDialog.DeleteProject(project.id))
        } else {
            viewModel.deleteProject(project.id)
        }
    }

    item(key = "projects_header") {
        SectionTitle(
            title = "Projects",
            onAdd = { openDialog(SettingsDialog.ProjectEditor(null)) },
        )
    }

    item(key = "projects_show_archived") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onShowArchivedProjectsChange(!showArchivedProjects) }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Show archived projects (${state.archivedProjects.size})",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = showArchivedProjects,
                onCheckedChange = onShowArchivedProjectsChange,
            )
        }
    }

    if (state.projects.isEmpty()) {
        item(key = "projects_empty") {
            Text(
                text = "No projects yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    } else {
        // Build tree: parent projects first, children nested underneath
        val sortedProjects = buildProjectTree(state.projects)
        items(sortedProjects, key = { "project_${it.first.id}" }, contentType = { "project" }) { (project, depth) ->
            ProjectRow(
                project = project,
                depth = depth,
                canDelete = project.id != state.inboxProjectId,
                canArchive = project.id != state.inboxProjectId,
                onEdit = { openDialog(SettingsDialog.ProjectEditor(project.id)) },
                onArchive = { openDialog(SettingsDialog.ArchiveProject(project.id)) },
                onRestore = {},
                onDelete = { deleteProject(project) },
            )
        }
    }

    if (showArchivedProjects) {
        item(key = "archived_projects_header") {
            Text(
                text = "Archived",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (state.archivedProjects.isEmpty()) {
            item(key = "archived_projects_empty") {
                Text(
                    text = "No archived projects",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        } else {
            val archivedTree = buildProjectTree(state.archivedProjects)
            items(archivedTree, key = { "archived_project_${it.first.id}" }, contentType = { "project" }) { (project, depth) ->
                ProjectRow(
                    project = project,
                    depth = depth,
                    canEdit = false,
                    canDelete = project.id != state.inboxProjectId,
                    canArchive = false,
                    onEdit = {},
                    onArchive = {},
                    onRestore = { viewModel.restoreProject(project) },
                    onDelete = { deleteProject(project) },
                )
            }
        }
    }

    item(key = "projects_divider") {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    }
}

internal fun LazyListScope.labelsSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    openDialog: (SettingsDialog) -> Unit,
) {
    fun deleteLabel(label: Label) {
        if (state.behaviorPrefs.confirmBeforeDelete) {
            openDialog(SettingsDialog.DeleteLabel(label.id))
        } else {
            viewModel.deleteLabel(label.id)
        }
    }

    item(key = "labels_header") {
        SectionTitle(
            title = "Labels",
            onAdd = { openDialog(SettingsDialog.LabelEditor(null)) },
        )
    }

    if (state.labels.isEmpty()) {
        item(key = "labels_empty") {
            Text(
                text = "No labels yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    } else {
        items(state.labels, key = { "label_${it.id}" }, contentType = { "label" }) { label ->
            LabelRow(
                label = label,
                onEdit = { openDialog(SettingsDialog.LabelEditor(label.id)) },
                onDelete = { deleteLabel(label) },
            )
        }
    }

    item(key = "labels_divider") {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    }
}

internal fun LazyListScope.customListsSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    openDialog: (SettingsDialog) -> Unit,
) {
    fun deleteCustomList(list: CustomList) {
        if (state.behaviorPrefs.confirmBeforeDelete) {
            openDialog(SettingsDialog.DeleteCustomList(list.id))
        } else {
            viewModel.deleteCustomList(list.id)
        }
    }

    item(key = "lists_header") {
        SectionTitle(
            title = "Custom Lists",
            onAdd = { openDialog(SettingsDialog.CustomListEditor(null)) },
        )
    }

    if (state.customListSyncStatus !is CustomListSyncStatus.Idle) {
        item(key = "lists_sync_status") {
            val status = state.customListSyncStatus
            val message = when (status) {
                CustomListSyncStatus.Idle -> ""
                CustomListSyncStatus.Syncing -> "Syncing custom lists…"
                CustomListSyncStatus.Pending -> "Custom-list changes are pending"
                is CustomListSyncStatus.Offline -> status.message
                is CustomListSyncStatus.Error -> status.message
                is CustomListSyncStatus.UpdateRequired -> status.message
            }
            val canRetry = status is CustomListSyncStatus.Pending ||
                status is CustomListSyncStatus.Offline ||
                status is CustomListSyncStatus.Error
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status is CustomListSyncStatus.Error ||
                        status is CustomListSyncStatus.UpdateRequired
                    ) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f),
                )
                if (canRetry) {
                    TextButton(onClick = viewModel::retryCustomListSync) { Text("Retry") }
                }
            }
        }
    }

    if (state.customLists.isEmpty()) {
        item(key = "lists_empty") {
            Text(
                text = "No custom lists yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    } else {
        items(state.customLists, key = { "list_${it.id}" }, contentType = { "list" }) { list ->
            CustomListRow(
                customList = list,
                onEdit = { openDialog(SettingsDialog.CustomListEditor(list.id)) },
                onDelete = { deleteCustomList(list) },
            )
        }
    }

    item(key = "lists_divider") {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    }
}

internal fun LazyListScope.dataSyncSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    openDialog: (SettingsDialog) -> Unit,
) {
    item(key = "data_header") {
        SectionHeader(icon = Icons.Outlined.Sync, title = "Data & Sync")
    }

    item(key = "sync_status") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (state.isOnline) Icons.Outlined.CloudDone else Icons.Outlined.CloudOff,
                contentDescription = null,
                tint = if (state.isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (state.isOnline) "Connected" else "Offline",
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (state.pendingActionCount > 0) {
                    Text(
                        text = "${state.pendingActionCount} pending change(s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.failedActionCount > 0) {
                    Text(
                        text = "${state.failedActionCount} failed change(s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    item(key = "sync_buttons") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = viewModel::triggerSync,
                enabled = state.isOnline,
            ) {
                Text("Sync Now")
            }
            if (state.failedActionCount > 0) {
                FilledTonalButton(onClick = viewModel::retryFailedActions) {
                    Text("Retry All")
                }
                TextButton(onClick = { openDialog(SettingsDialog.ClearFailedActions) }) {
                    Text("Clear Failed")
                }
            }
        }
    }

    item(key = "clear_cache") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = { openDialog(SettingsDialog.ClearCache) })
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.DeleteSweep,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Clear Cache & Re-sync",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "Delete local data and fetch everything from the server",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // The auth debug log only exists in debug builds.
    if (BuildInfo.isDebug) item(key = "auth_debug_log") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = { openDialog(SettingsDialog.AuthDebugLog) })
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Auth Debug Log",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "View token refresh and auth state history",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    item(key = "data_divider") {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    }
}
