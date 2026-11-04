package com.rendyhd.vicu.ui.components.shared

import com.rendyhd.vicu.util.countOf
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * What the banner says while changes cannot be sent: no network, or a network and a server that
 * does not answer. Null when neither holds.
 */
internal fun offlineBannerText(isOffline: Boolean, serverUnreachable: Boolean, pendingCount: Int): String? {
    val state = when {
        isOffline -> "Offline"
        serverUnreachable -> "Can't reach the server"
        else -> return null
    }
    return if (pendingCount > 0) "$state — ${countOf(pendingCount, "change")} pending" else state
}

/**
 * A quiet band: nothing is wrong with the user's work, it waits. The bands sit at the top of the
 * screen, so each pays for the status bar itself; the band above and the screen below take that
 * off through consumed insets (see the banners' place in VicuApp).
 */
@Composable
fun OfflineBanner(
    isOffline: Boolean,
    serverUnreachable: Boolean,
    pendingCount: Int,
    modifier: Modifier = Modifier,
) {
    val text = offlineBannerText(isOffline, serverUnreachable, pendingCount)
    // The last words stay while the band closes.
    val shown = remember { mutableStateOf("") }
    if (text != null) shown.value = text
    AnimatedVisibility(
        visible = text != null,
        enter = expandVertically(),
        exit = shrinkVertically(),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = shown.value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Shown while changes the server refused are waiting. The user can try them again or discard
 * them; discarding asks first, because the changes are lost on this device.
 */
@Composable
fun FailedActionsBanner(
    failedCount: Int,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmingDiscard by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = failedCount > 0,
        enter = expandVertically(),
        exit = shrinkVertically(),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.errorContainer)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            FailedActionsText(failedCount)
            TextButton(onClick = onRetry) { Text("Retry") }
            TextButton(onClick = { confirmingDiscard = true }) { Text("Discard") }
        }
    }

    if (confirmingDiscard) {
        AlertDialog(
            onDismissRequest = { confirmingDiscard = false },
            title = { Text("Discard unsynced changes?") },
            text = {
                Text(
                    "The server refused ${changesLabel(failedCount)}. If you discard them, the " +
                        "tasks go back to how the server has them and your edits to them are lost.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDiscard = false
                        onDiscard()
                    },
                ) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmingDiscard = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun RowScope.FailedActionsText(failedCount: Int) {
    Text(
        text = "${changesLabel(failedCount)} could not be synced",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.weight(1f),
    )
}

internal fun changesLabel(count: Int): String = if (count == 1) "1 change" else "$count changes"
