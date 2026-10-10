package com.rendyhd.vicu.ui.screens.settings

import com.rendyhd.vicu.util.countOf
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.auth.AuthDebugLog
import com.rendyhd.vicu.util.BuildInfo

internal fun LazyListScope.dataSyncSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    openDialog: (SettingsDialog) -> Unit,
) {
    item(key = "data_header") {
        SectionHeader(icon = Icons.Outlined.Sync, title = "Data & Sync")
    }

    item(key = "sync_status") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (state.isOnline) Icons.Outlined.CloudDone else Icons.Outlined.CloudOff,
                contentDescription = null,
                tint = if (state.isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (state.isOnline) "Connected" else "Offline",
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (state.pendingActionCount > 0) {
                    Text(
                        text = countOf(state.pendingActionCount, "pending change"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.failedActionCount > 0) {
                    Text(
                        text = countOf(state.failedActionCount, "failed change"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    item(key = "sync_buttons") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = viewModel::triggerSync,
                enabled = state.isOnline,
            ) {
                Text("Sync Now")
            }
            if (state.failedActionCount > 0) {
                FilledTonalButton(onClick = viewModel::retryFailedActions) {
                    Text("Retry All")
                }
                TextButton(onClick = { openDialog(SettingsDialog.ClearFailedActions) }) {
                    Text("Clear Failed")
                }
            }
        }
    }

    item(key = "clear_cache") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = { openDialog(SettingsDialog.ClearCache) })
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.DeleteSweep,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Clear Cache & Re-sync",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "Delete local data and fetch everything from the server",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // The auth debug log only exists in debug builds.
    if (BuildInfo.isDebug) item(key = "auth_debug_log") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = { openDialog(SettingsDialog.AuthDebugLog) })
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Auth Debug Log",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "View token refresh and auth state history",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    item(key = "data_divider") {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    }
}
