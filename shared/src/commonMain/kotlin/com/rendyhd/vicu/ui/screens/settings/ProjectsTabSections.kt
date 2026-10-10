package com.rendyhd.vicu.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.data.local.SubprojectDisplayMode
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.CustomListSyncStatus
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.util.countOf

// The sections of the Projects tab other than the project tree itself (ProjectsTab).

/** The Inbox: which project it is, and whether dated tasks leave it. */
internal fun LazyListScope.inboxSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    openDialog: (SettingsDialog) -> Unit,
) {
    item(key = "inbox_header") {
        SubsectionHeading("Inbox")
    }

    item(key = "inbox_project") {
        val activeInbox = state.projects.find { it.id == state.inboxProjectId }
        val archivedInbox = state.archivedProjects.find { it.id == state.inboxProjectId }
        val inboxName = activeInbox?.title
            ?: archivedInbox?.let { "${it.title} (archived — select another project)" }
            ?: "Not set"
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = { openDialog(SettingsDialog.InboxPicker) })
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Inbox Project",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = inboxName,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (archivedInbox != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = "Choose inbox project",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }

    item(key = "inbox_exclude_dated") {
        SwitchRow(
            label = "Move dated tasks out of Inbox",
            description = "Tasks with a due date no longer appear in Inbox.",
            checked = state.behaviorPrefs.inboxExcludeDated,
            onCheckedChange = viewModel::setInboxExcludeDated,
        )
    }

    item(key = "inbox_divider") {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    }
}

/** How projects are shown: subprojects in a project view, and the drawer's progress rings. */
internal fun LazyListScope.displaySection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    item(key = "display_header") {
        SubsectionHeading("Display")
    }

    item(key = "subproject_display_mode") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(text = "Subprojects in project views", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "Combine child tasks into sections or open each child as its own project",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            @OptIn(ExperimentalMaterial3Api::class)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val options = listOf(
                    SubprojectDisplayMode.SECTIONS to "Sections",
                    SubprojectDisplayMode.PROJECT_ROWS to "Project rows",
                )
                options.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = state.behaviorPrefs.subprojectDisplayMode == mode,
                        onClick = { viewModel.setSubprojectDisplayMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    ) {
                        Text(label)
                    }
                }
            }
        }
    }

    item(key = "show_project_progress") {
        SwitchRow(
            label = "Show project progress",
            description = "Progress rings next to the projects in the drawer",
            checked = state.behaviorPrefs.showProjectProgress,
            onCheckedChange = viewModel::setShowProjectProgress,
        )
    }

    item(key = "display_divider") {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    }
}

/** The summary of the "Excluded from review" row: the project's name when there is one, else how many. */
internal fun excludedFromReviewSummary(projects: List<Project>): String =
    projects.singleOrNull()?.title ?: countOf(projects.size, "project")

/** Review tracking, and the projects left out of it. */
internal fun LazyListScope.reviewSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    openDialog: (SettingsDialog) -> Unit,
) {
    item(key = "review_header") {
        SubsectionHeading("Review")
    }
    item(key = "review_enabled") {
        SwitchRow(
            label = "Enable project review tracking",
            description = "Periodically review your projects",
            checked = state.reviewPrefs.enabled,
            onCheckedChange = viewModel::setReviewEnabled,
        )
    }
    if (state.reviewPrefs.enabled) {
        item(key = "review_cadence") {
            SettingsValueRow(
                label = "Default review cadence",
                value = "${state.reviewPrefs.defaultCadenceDays} days",
                onClick = { openDialog(SettingsDialog.ReviewCadence) },
            )
        }
        item(key = "review_exclude_inbox") {
            SwitchRow(
                label = "Exclude Inbox from review",
                description = "Don't track the inbox project",
                checked = state.reviewPrefs.excludeInbox,
                onCheckedChange = viewModel::setReviewExcludeInbox,
            )
        }
        if (state.excludedFromReview.isNotEmpty()) {
            item(key = "review_excluded") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = { openDialog(SettingsDialog.ExcludedFromReview) })
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Excluded from review",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = excludedFromReviewSummary(state.excludedFromReview),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
    item(key = "review_divider") {
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
