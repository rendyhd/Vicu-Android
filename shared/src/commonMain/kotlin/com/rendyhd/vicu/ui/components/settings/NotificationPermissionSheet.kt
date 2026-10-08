package com.rendyhd.vicu.ui.components.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rendyhd.vicu.permission.NotificationPermissionCoordinator
import com.rendyhd.vicu.permission.NotificationPermissionStep

/** The words of the sheet for each step; kept apart from the UI so they stay one sentence long. */
internal object NotificationPermissionCopy {
    const val TITLE = "Get reminders on time"
    const val RATIONALE = "Vicu needs permission to show notifications so it can remind you when a task is due."
    const val SETTINGS_TITLE = "Notifications are off"
    const val SETTINGS_RATIONALE = "Turn notifications on for Vicu in system settings so reminders and summaries can reach you."
}

/**
 * Explains why Vicu wants to show notifications, before the system prompt (or the settings page
 * when the system will not ask again). Shown from the coordinator's prompt; nothing when null.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationPermissionSheet(coordinator: NotificationPermissionCoordinator) {
    val prompt = coordinator.prompt.collectAsStateWithLifecycle().value ?: return
    val toSettings = prompt.step == NotificationPermissionStep.OPEN_SYSTEM_SETTINGS

    ModalBottomSheet(onDismissRequest = coordinator::dismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (toSettings) NotificationPermissionCopy.SETTINGS_TITLE else NotificationPermissionCopy.TITLE,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = if (toSettings) NotificationPermissionCopy.SETTINGS_RATIONALE else NotificationPermissionCopy.RATIONALE,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = coordinator::confirm,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Text(if (toSettings) "Open settings" else "Allow notifications")
            }
            TextButton(
                onClick = coordinator::dismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Not now")
            }
        }
    }
}
