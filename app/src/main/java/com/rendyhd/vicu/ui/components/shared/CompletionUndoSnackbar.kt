package com.rendyhd.vicu.ui.components.shared

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.rendyhd.vicu.domain.model.Task
import kotlinx.coroutines.flow.Flow

/**
 * Shows a brief "Task completed" Snackbar with an Undo action each time [events] emits
 * a completed task. Tapping Undo invokes [onUndo] (which reverts done = false).
 *
 * Used by the task-list screens so that completing a task surfaces a visible, discoverable
 * undo option — matching the hint shown in Settings → Gestures (issue #6). The existing
 * tap-the-checkbox-again revert still works alongside this.
 */
@Composable
fun CompletionUndoSnackbar(
    snackbarHostState: SnackbarHostState,
    events: Flow<Task>,
    onUndo: (Task) -> Unit,
) {
    LaunchedEffect(events, snackbarHostState) {
        events.collect { task ->
            val result = snackbarHostState.showSnackbar(
                message = "Task completed",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                onUndo(task)
            }
        }
    }
}
