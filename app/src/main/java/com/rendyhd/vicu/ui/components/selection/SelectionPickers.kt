package com.rendyhd.vicu.ui.components.selection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.rendyhd.vicu.ui.components.picker.LabelPickerDialog
import com.rendyhd.vicu.ui.components.picker.ProjectPickerDialog

/**
 * Move / Apply-label picker dialogs for multi-select bulk actions, reused across list screens.
 * Each picks one target and applies it to the whole selection, then dismisses.
 */
@Composable
fun SelectionPickers(
    selectionVm: SelectionViewModel,
    showMove: Boolean,
    showLabel: Boolean,
    onDismissMove: () -> Unit,
    onDismissLabel: () -> Unit,
) {
    if (showMove) {
        val projects by selectionVm.projects.collectAsState()
        ProjectPickerDialog(
            projects = projects,
            selectedProjectId = null,
            onProjectSelected = {
                selectionVm.bulkMove(it)
                onDismissMove()
            },
            onDismiss = onDismissMove,
        )
    }
    if (showLabel) {
        val labels by selectionVm.labels.collectAsState()
        LabelPickerDialog(
            allLabels = labels,
            selectedLabelIds = emptySet(),
            onToggleLabel = {
                selectionVm.bulkApplyLabel(it)
                onDismissLabel()
            },
            onCreateLabel = { _, _ -> },
            onDismiss = onDismissLabel,
        )
    }
}
