package com.rendyhd.vicu.ui.components.selection

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.rendyhd.vicu.ui.components.picker.LabelPickerDialog
import com.rendyhd.vicu.ui.components.picker.PriorityPickerDialog
import com.rendyhd.vicu.ui.components.picker.ProjectPickerDialog
import com.rendyhd.vicu.ui.components.picker.WhenSheet

/** Dialog-based actions available from the multi-select overflow menu. */
enum class SelectionAction {
    SCHEDULE,
    SET_PRIORITY,
    MOVE_PROJECT,
    APPLY_LABEL,
    REMOVE,
}

@Composable
fun SelectionPickers(
    selectionVm: SelectionViewModel,
    action: SelectionAction?,
    selectedCount: Int,
    onDismiss: () -> Unit,
) {
    // Both collectors are read before the early return below: a state read after a conditional
    // return only exists on some compositions.
    val pendingCompletionCount by selectionVm.pendingCompletionDescendantCount.collectAsStateWithLifecycle()
    val selectedDescendantCount by selectionVm.selectedDescendantCount.collectAsStateWithLifecycle()
    // The choice that applies a bulk action (not opening a picker) is confirmed with a haptic.
    val haptic = LocalHapticFeedback.current
    val confirm = { haptic.performHapticFeedback(HapticFeedbackType.Confirm) }
    if (pendingCompletionCount != null) {
        val count = pendingCompletionCount ?: 0
        AlertDialog(
            onDismissRequest = selectionVm::dismissBulkComplete,
            title = { Text("Complete tasks and subtasks?") },
            text = {
                Text("This will also complete $count unfinished ${if (count == 1) "subtask" else "subtasks"}.")
            },
            confirmButton = {
                TextButton(onClick = { confirm(); selectionVm.confirmBulkComplete() }) { Text("Complete all") }
            },
            dismissButton = {
                TextButton(onClick = selectionVm::dismissBulkComplete) { Text("Cancel") }
            },
        )
        return
    }

    when (action) {
        SelectionAction.SCHEDULE -> WhenSheet(
            currentDate = null,
            onDateSelected = { confirm(); selectionVm.bulkSchedule(it) },
            onClearDate = {},
            onDismiss = onDismiss,
        )

        SelectionAction.SET_PRIORITY -> PriorityPickerDialog(
            current = null,
            onPick = { confirm(); selectionVm.bulkSetPriority(it) },
            onDismiss = onDismiss,
        )

        SelectionAction.MOVE_PROJECT -> {
            val projects by selectionVm.projects.collectAsStateWithLifecycle()
            ProjectPickerDialog(
                projects = projects,
                selectedProjectId = null,
                onProjectSelected = { confirm(); selectionVm.bulkMove(it) },
                onDismiss = onDismiss,
            )
        }

        SelectionAction.APPLY_LABEL -> {
            val labels by selectionVm.labels.collectAsStateWithLifecycle()
            LabelPickerDialog(
                allLabels = labels,
                selectedLabelIds = emptySet(),
                onToggleLabel = {
                    confirm()
                    selectionVm.bulkApplyLabel(it)
                    onDismiss()
                },
                onCreateLabel = { name, hexColor ->
                    confirm()
                    selectionVm.createLabelAndApply(name, hexColor)
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }

        SelectionAction.REMOVE -> AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(if (selectedDescendantCount > 0) "Open parent task to remove" else "Remove selected tasks?")
            },
            text = {
                Text(
                    if (selectedDescendantCount > 0) {
                        "The selection includes a parent task with $selectedDescendantCount nested " +
                            "${if (selectedDescendantCount == 1) "subtask" else "subtasks"}. " +
                            "Open the parent to choose whether to delete or keep them."
                    } else if (selectedCount == 1) {
                        "This task will be permanently removed."
                    } else {
                        "$selectedCount tasks will be permanently removed."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (selectedDescendantCount == 0) {
                            confirm()
                            selectionVm.bulkRemove()
                        }
                        onDismiss()
                    },
                ) {
                    Text(if (selectedDescendantCount > 0) "Close" else "Remove")
                }
            },
            dismissButton = if (selectedDescendantCount == 0) {
                { TextButton(onClick = onDismiss) { Text("Cancel") } }
            } else {
                null
            },
        )

        null -> Unit
    }
}
