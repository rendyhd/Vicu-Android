package com.rendyhd.vicu.ui.components.shared

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.rendyhd.vicu.domain.model.Project

/**
 * Asks before archiving [project]. Archiving keeps the tasks and can be reversed, so the button is
 * not drawn as destructive. Used by Settings and the project screen.
 */
@Composable
fun ArchiveProjectDialog(
    project: Project,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Archive Project") },
        text = {
            Text(
                "Archive \"${project.title}\"? Its tasks will be kept, " +
                    "but the project will disappear from normal views. You can restore it from Settings.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Archive") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Asks before deleting [project] and its tasks (shown only while "Confirm before deleting" is on).
 * Used by Settings and the project screen.
 */
@Composable
fun DeleteProjectDialog(
    project: Project,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Project") },
        text = { Text("Delete \"${project.title}\"? All tasks in this project will be deleted.") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
