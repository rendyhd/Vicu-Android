package com.rendyhd.vicu.ui.components.selection

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.rendyhd.vicu.ui.components.picker.LabelPickerDialog
import com.rendyhd.vicu.ui.components.picker.PriorityPickerDialog
import com.rendyhd.vicu.ui.components.picker.ProjectPickerDialog
import com.rendyhd.vicu.ui.components.picker.VicuDatePickerDialog

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
    val pendingCompletionCount by selectionVm.pendingCompletionDescendantCount.collectAsState()
    val selectedDescendantCount by selectionVm.selectedDescendantCount.collectAsState()
    if (pendingCompletionCount != null) {
        val count = pendingCompletionCount ?: 0
        AlertDialog(
            onDismissRequest = selectionVm::dismissBulkComplete,
            title = { Text("Complete tasks and subtasks?") },
            text = {
                Text("This will also complete $count unfinished ${if (count == 1) "subtask" else "subtasks"}.")
            },
            confirmButton = {
                TextButton(onClick = selectionVm::confirmBulkComplete) { Text("Complete all") }
            },
            dismissButton = {
                TextButton(onClick = selectionVm::dismissBulkComplete) { Text("Cancel") }
            },
        )
        return
    }

    when (action) {
        SelectionAction.SCHEDULE -> VicuDatePickerDialog(
            currentDate = null,
            onDateSelected = selectionVm::bulkSchedule,
            onClearDate = {},
            onDismiss = onDismiss,
        )

        SelectionAction.SET_PRIORITY -> PriorityPickerDialog(
            current = null,
            onPick = selectionVm::bulkSetPriority,
            onDismiss = onDismiss,
        )

        SelectionAction.MOVE_PROJECT -> {
            val projects by selectionVm.projects.collectAsState()
            ProjectPickerDialog(
                projects = projects,
                selectedProjectId = null,
                onProjectSelected = selectionVm::bulkMove,
                onDismiss = onDismiss,
            )
        }

        SelectionAction.APPLY_LABEL -> {
            val labels by selectionVm.labels.collectAsState()
            LabelPickerDialog(
                allLabels = labels,
                selectedLabelIds = emptySet(),
                onToggleLabel = {
                    selectionVm.bulkApplyLabel(it)
                    onDismiss()
                },
                onCreateLabel = { name, hexColor ->
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
                        if (selectedDescendantCount == 0) selectionVm.bulkRemove()
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
