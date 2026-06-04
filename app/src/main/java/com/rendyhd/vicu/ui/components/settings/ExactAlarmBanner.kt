package com.rendyhd.vicu.ui.components.settings

import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Banner shown in Settings when exact-alarm permission is missing on Android 12+.
 *
 * Vicu no longer declares the auto-granted `USE_EXACT_ALARM` (Play restricts it), so on
 * Android 12+ reminders fall back to inexact timing until the user grants exact alarms here.
 * Tapping deep-links to the system toggle; the banner re-checks on resume and hides itself
 * once granted.
 */
@Composable
fun ExactAlarmBanner() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

    val context = LocalContext.current
    val alarmManager = remember { context.getSystemService(AlarmManager::class.java) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var canSchedule by remember { mutableStateOf(alarmManager.canScheduleExactAlarms()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canSchedule = alarmManager.canScheduleExactAlarms()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (canSchedule) return

    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable {
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                }.onFailure {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                }
            },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Exact reminders are off",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = "Without permission, reminders may fire late. Tap to allow exact alarms.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
