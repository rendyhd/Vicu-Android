package com.rendyhd.vicu.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** "3 changes (2 waiting, 1 failed)" for the dialogs below. */
internal fun unsyncedChangesSummary(pending: Int, failed: Int): String {
    val total = pending + failed
    val noun = if (total == 1) "change" else "changes"
    val parts = buildList {
        if (pending > 0) add("$pending waiting")
        if (failed > 0) add("$failed failed")
    }
    return "$total unsynced $noun (${parts.joinToString(", ")})"
}

/**
 * Sign-out deletes everything on this device, including offline changes that never reached the
 * server, so when there are any the user has to tick an explicit "discard" box first.
 */
@Composable
internal fun SignOutDialog(
    pendingCount: Int,
    failedCount: Int,
    routineHistoryCount: Int,
    customListChangesUnsynced: Boolean,
    onConfirm: (discardUnsynced: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val unsynced = pendingCount + failedCount
    val anythingUnsynced = unsynced > 0 || customListChangesUnsynced
    var discard by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sign Out") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Sign out of your Vikunja account? Tasks and other data cached on this device are deleted.")
                // Routine history lives on the server. Only entries from before it moved there and
                // that have not been uploaded yet exist nowhere else.
                if (routineHistoryCount > 0) {
                    val noun = if (routineHistoryCount == 1) "entry" else "entries"
                    Text(
                        "Routine history that has not been uploaded to your server yet " +
                            "($routineHistoryCount $noun) is deleted too. " +
                            "Open Routines while you are online to upload it first.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (unsynced > 0) {
                    Text(
                        "${unsyncedChangesSummary(pendingCount, failedCount)} " +
                            "have not reached the server and will be lost.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (customListChangesUnsynced) {
                    Text(
                        "Changes to your custom lists have not reached the server yet and will be lost.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (anythingUnsynced) {
                    DiscardCheckRow(
                        checked = discard,
                        onCheckedChange = { discard = it },
                        label = "Discard unsynced changes",
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(anythingUnsynced) },
                enabled = !anythingUnsynced || discard,
            ) {
                Text("Sign Out", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Clearing the cache keeps offline changes by default. Discarding the offline queue too is an
 * explicit, separate choice. Routine history is stored on the server and is not touched.
 */
@Composable
internal fun ClearCacheDialog(
    pendingCount: Int,
    failedCount: Int,
    onConfirm: (discardUnsynced: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val unsynced = pendingCount + failedCount
    var discard by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear Cache") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Clear cached tasks, projects and labels and re-sync from the server? " +
                        "You will not be signed out.",
                )
                if (unsynced > 0) {
                    Text(
                        "${unsyncedChangesSummary(pendingCount, failedCount)}. " +
                            "They are kept and sent on the next sync unless you discard them.",
                    )
                    DiscardCheckRow(
                        checked = discard,
                        onCheckedChange = { discard = it },
                        label = "Also discard unsynced changes",
                    )
                } else {
                    Text("Routine history is stored on your server and is not affected.")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(discard && unsynced > 0) }) { Text("Clear & Sync") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * "Clear Failed" discards the changes that could not be sent: they are deleted from the queue for
 * good, so it asks first. The rows they touched are refreshed from the server afterwards.
 */
@Composable
internal fun ClearFailedActionsDialog(
    failedCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val noun = if (failedCount == 1) "change" else "changes"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Discard failed changes") },
        text = {
            Text(
                "Discard $failedCount failed $noun? They could not be sent to the server and will be " +
                    "lost. The affected items are refreshed from the server on the next sync.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Discard", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun DiscardCheckRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Checkbox) { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(text = label, modifier = Modifier.padding(start = 12.dp))
    }
}
