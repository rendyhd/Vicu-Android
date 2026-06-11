package com.rendyhd.vicu.ui.components.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Shown when system notifications are disabled for the app: reminders and summaries cannot
 * fire, so every toggle below is inert until the user re-enables them in system settings.
 * Re-checks on resume so it disappears as soon as the user comes back from settings.
 */
@Composable
fun NotificationsDisabledBanner() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var notificationsEnabled by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    if (notificationsEnabled) return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Notifications are turned off",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = "Reminders and daily summaries cannot be shown until notifications are enabled for Vicu in system settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            }) {
                Text("Open settings")
            }
        }
    }
}
