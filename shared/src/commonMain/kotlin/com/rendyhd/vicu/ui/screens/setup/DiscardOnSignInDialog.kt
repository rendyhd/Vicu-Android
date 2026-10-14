package com.rendyhd.vicu.ui.screens.setup

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Shown when the credentials just verified belong to a different account than the one on this
 * device and the old account still has changes that never reached the server. Nothing has been
 * deleted yet; going back leaves everything as it was.
 */
@Composable
internal fun DiscardOnSignInDialog(
    prompt: DiscardPrompt,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Discard unsynced changes?") },
        text = {
            Text(
                "You are signing in to a different account than the one used on this device. " +
                    "${changesLabel(prompt.unsyncedChanges)} made there never reached the server " +
                    "and will be discarded.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Discard and sign in", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Go back") }
        },
    )
}

private fun changesLabel(count: Int): String =
    if (count == 1) "One change" else "$count changes"
