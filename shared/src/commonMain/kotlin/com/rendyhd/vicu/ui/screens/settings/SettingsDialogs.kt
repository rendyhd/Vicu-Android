package com.rendyhd.vicu.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.ui.components.shared.BottomBarSlotEditor
import com.rendyhd.vicu.ui.components.shared.CustomListDialog
import com.rendyhd.vicu.ui.components.shared.LabelEditDialog
import com.rendyhd.vicu.ui.components.shared.ProjectEditDialog
import com.rendyhd.vicu.ui.components.shared.ReviewCadenceInputDialog

private val REVIEW_CADENCE_PRESETS = listOf(7, 14, 30, 60, 90)
private val RETENTION_PRESETS = listOf(30, 60, 90, 180, 365)

/**
 * Draws [dialog], the one dialog Settings has open. The project, label or list a dialog is about
 * is looked up by id in [state]; one that is gone by now (deleted on another device and synced)
 * draws nothing.
 */
@Composable
internal fun SettingsDialogHost(
    dialog: SettingsDialog?,
    state: SettingsUiState,
    routineHistoryCount: Int,
    viewModel: SettingsViewModel,
    open: (SettingsDialog) -> Unit,
    dismiss: () -> Unit,
) {
    when (dialog) {
        null -> Unit

        SettingsDialog.DailySummaryTime -> SummaryTimeDialog(
            title = "Daily Summary Time",
            initialHour = state.notificationPrefs.dailySummaryHour,
            initialMinute = state.notificationPrefs.dailySummaryMinute,
            onConfirm = { hour, minute ->
                viewModel.setDailySummaryTime(hour, minute)
                dismiss()
            },
            onDismiss = dismiss,
        )

        SettingsDialog.AfternoonSummaryTime -> SummaryTimeDialog(
            title = "Afternoon Summary Time",
            initialHour = state.notificationPrefs.afternoonSummaryHour,
            initialMinute = state.notificationPrefs.afternoonSummaryMinute,
            onConfirm = { hour, minute ->
                viewModel.setAfternoonSummaryTime(hour, minute)
                dismiss()
            },
            onDismiss = dismiss,
        )

        SettingsDialog.ReminderOffset -> ChoiceDialog(
            title = "Default Reminder",
            options = REMINDER_OFFSET_OPTIONS.map { (label, seconds) ->
                ChoiceOption(
                    label = label,
                    selected = seconds == state.notificationPrefs.defaultReminderOffset,
                    onChoose = { viewModel.setDefaultReminderOffset(seconds) },
                )
            },
            onDismiss = dismiss,
        )

        SettingsDialog.ReminderRelativeTo -> ChoiceDialog(
            title = "Relative To",
            options = REMINDER_RELATIVE_OPTIONS.map { (label, value) ->
                ChoiceOption(
                    label = label,
                    selected = value == state.notificationPrefs.defaultReminderRelativeTo,
                    onChoose = { viewModel.setDefaultReminderRelativeTo(value) },
                )
            },
            onDismiss = dismiss,
        )

        SettingsDialog.ReviewCadence -> ChoiceDialog(
            title = "Default review cadence",
            options = REVIEW_CADENCE_PRESETS.map { days ->
                ChoiceOption(
                    label = "$days days",
                    selected = days == state.reviewPrefs.defaultCadenceDays,
                    onChoose = { viewModel.setReviewDefaultCadence(days) },
                )
            } + ChoiceOption(
                label = "Custom…",
                selected = state.reviewPrefs.defaultCadenceDays !in REVIEW_CADENCE_PRESETS,
                onChoose = null,
                onOther = { open(SettingsDialog.ReviewCadenceCustom) },
            ),
            onDismiss = dismiss,
        )

        SettingsDialog.ReviewCadenceCustom -> ReviewCadenceInputDialog(
            title = "Custom review cadence",
            initialDays = state.reviewPrefs.defaultCadenceDays,
            onConfirm = { days ->
                viewModel.setReviewDefaultCadence(days)
                dismiss()
            },
            onDismiss = dismiss,
        )

        SettingsDialog.LogbookRetention -> ChoiceDialog(
            title = "Keep completed tasks for",
            options = RETENTION_PRESETS.map { days ->
                ChoiceOption(
                    label = "$days days",
                    selected = days == state.logbookPrefs.retentionDays,
                    onChoose = { viewModel.setLogbookRetentionDays(days) },
                )
            },
            onDismiss = dismiss,
        )

        is SettingsDialog.ProjectEditor -> {
            val project = dialog.projectId?.let { id -> state.projects.find { it.id == id } }
            if (dialog.projectId == null || project != null) {
                ProjectEditDialog(
                    project = project,
                    projects = state.projects,
                    onSave = { name, hexColor, parentId ->
                        if (project != null) {
                            viewModel.updateProject(project, name, hexColor, parentId)
                        } else {
                            viewModel.createProject(name, hexColor, parentId)
                        }
                        dismiss()
                    },
                    onDismiss = dismiss,
                )
            }
        }

        is SettingsDialog.DeleteProject -> {
            // An archived project can be deleted too.
            val project = state.projects.find { it.id == dialog.projectId }
                ?: state.archivedProjects.find { it.id == dialog.projectId }
            if (project != null) {
                ConfirmDialog(
                    title = "Delete Project",
                    message = "Delete \"${project.title}\"? All tasks in this project will be deleted.",
                    confirmLabel = "Delete",
                    destructive = true,
                    onConfirm = {
                        viewModel.deleteProject(project.id)
                        dismiss()
                    },
                    onDismiss = dismiss,
                )
            }
        }

        // Archiving keeps tasks and can be reversed.
        is SettingsDialog.ArchiveProject -> state.projects.find { it.id == dialog.projectId }?.let { project ->
            ConfirmDialog(
                title = "Archive Project",
                message = "Archive \"${project.title}\"? Its tasks will be kept, " +
                    "but the project will disappear from normal views. You can restore it from Settings.",
                confirmLabel = "Archive",
                destructive = false,
                onConfirm = {
                    viewModel.archiveProject(project)
                    dismiss()
                },
                onDismiss = dismiss,
            )
        }

        is SettingsDialog.LabelEditor -> {
            val label = dialog.labelId?.let { id -> state.labels.find { it.id == id } }
            if (dialog.labelId == null || label != null) {
                LabelEditDialog(
                    label = label,
                    onSave = { name, hexColor ->
                        if (label != null) {
                            viewModel.updateLabel(label, name, hexColor)
                        } else {
                            viewModel.createLabel(name, hexColor)
                        }
                        dismiss()
                    },
                    onDismiss = dismiss,
                )
            }
        }

        is SettingsDialog.DeleteLabel -> state.labels.find { it.id == dialog.labelId }?.let { label ->
            ConfirmDialog(
                title = "Delete Label",
                message = "Delete \"${label.title}\"? It will be removed from all tasks.",
                confirmLabel = "Delete",
                destructive = true,
                onConfirm = {
                    viewModel.deleteLabel(label.id)
                    dismiss()
                },
                onDismiss = dismiss,
            )
        }

        is SettingsDialog.CustomListEditor -> {
            val list = dialog.listId?.let { id -> state.customLists.find { it.id == id } }
            if (dialog.listId == null || list != null) {
                CustomListDialog(
                    customList = list,
                    projects = state.projects,
                    labels = state.labels,
                    onSave = { saved ->
                        viewModel.saveCustomList(saved)
                        dismiss()
                    },
                    onDismiss = dismiss,
                    inboxProjectId = state.inboxProjectId ?: 0L,
                )
            }
        }

        is SettingsDialog.DeleteCustomList -> state.customLists.find { it.id == dialog.listId }?.let { list ->
            ConfirmDialog(
                title = "Delete List",
                message = "Delete \"${list.name}\"?",
                confirmLabel = "Delete",
                destructive = true,
                onConfirm = {
                    viewModel.deleteCustomList(list.id)
                    dismiss()
                },
                onDismiss = dismiss,
            )
        }

        // Unsynced changes are shown and must be discarded explicitly.
        SettingsDialog.SignOut -> SignOutDialog(
            pendingCount = state.pendingActionCount,
            failedCount = state.failedActionCount,
            routineHistoryCount = routineHistoryCount,
            onConfirm = { discardUnsynced ->
                viewModel.logout(discardUnsynced)
                dismiss()
            },
            onDismiss = dismiss,
        )

        // Keeps unsynced changes and routine history unless told otherwise.
        SettingsDialog.ClearCache -> ClearCacheDialog(
            pendingCount = state.pendingActionCount,
            failedCount = state.failedActionCount,
            onConfirm = { discardUnsynced ->
                viewModel.clearCacheAndResync(discardUnsynced)
                dismiss()
            },
            onDismiss = dismiss,
        )

        SettingsDialog.ClearFailedActions -> ClearFailedActionsDialog(
            failedCount = state.failedActionCount,
            onConfirm = {
                viewModel.clearFailedActions()
                dismiss()
            },
            onDismiss = dismiss,
        )

        is SettingsDialog.BottomBarSlot -> if (dialog.index in state.bottomBarSlots.indices) {
            BottomBarSlotEditor(
                currentSlot = state.bottomBarSlots[dialog.index],
                slotIndex = dialog.index,
                // The bar already starts with the Inbox, so a slot for it would only be left out.
                projects = state.projects.filter { it.id != state.inboxProjectId },
                customLists = state.customLists,
                onSave = { slot ->
                    viewModel.updateBottomBarSlot(dialog.index, slot)
                    dismiss()
                },
                onDismiss = dismiss,
            )
        }

        SettingsDialog.InboxPicker -> InboxPickerDialog(
            state = state,
            onPick = { projectId ->
                viewModel.setInboxProject(projectId)
                dismiss()
            },
            onDismiss = dismiss,
        )

        SettingsDialog.AuthDebugLog -> AuthDebugLogDialog(onDismiss = dismiss)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SummaryTimeDialog(
    title: String,
    initialHour: Int,
    initialMinute: Int,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val timePickerState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                TimePicker(state = timePickerState)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(timePickerState.hour, timePickerState.minute) }) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/**
 * One row of a [ChoiceDialog]. Choosing it runs [onChoose] and closes the dialog, unless it has
 * none: then [onOther] runs and the dialog stays for whatever [onOther] opens in its place.
 */
private class ChoiceOption(
    val label: String,
    val selected: Boolean,
    val onChoose: (() -> Unit)?,
    val onOther: (() -> Unit)? = null,
)

@Composable
private fun ChoiceDialog(
    title: String,
    options: List<ChoiceOption>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val choose = option.onChoose
                                if (choose != null) {
                                    choose()
                                    onDismiss()
                                } else {
                                    option.onOther?.invoke()
                                }
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = option.selected,
                            onClick = null,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(option.label)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    destructive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                if (destructive) {
                    Text(confirmLabel, color = MaterialTheme.colorScheme.error)
                } else {
                    Text(confirmLabel)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun InboxPickerDialog(
    state: SettingsUiState,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Inbox Project") },
        text = {
            LazyColumn {
                items(state.projects, key = { it.id }, contentType = { "project" }) { project ->
                    val isSelected = project.id == state.inboxProjectId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(project.id) }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = project.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (isSelected) {
                            Icon(
                                Icons.Outlined.CheckCircle,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
