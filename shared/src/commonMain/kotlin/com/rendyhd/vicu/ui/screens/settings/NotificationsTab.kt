package com.rendyhd.vicu.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.permission.NotificationPermissionCoordinator
import com.rendyhd.vicu.ui.components.settings.ExactAlarmBanner
import com.rendyhd.vicu.ui.components.settings.NotificationsDisabledBanner
import org.koin.compose.koinInject

internal val REMINDER_OFFSET_OPTIONS = listOf(
    "None" to 0,
    "At due time" to -1,
    "5 min before" to 300,
    "15 min before" to 900,
    "30 min before" to 1800,
    "1 hour before" to 3600,
    "3 hours before" to 10800,
    "1 day before" to 86400,
)
internal val REMINDER_RELATIVE_OPTIONS = listOf(
    "Due date" to "due_date",
    "Start date" to "start_date",
    "End date" to "end_date",
)

// ========== Notifications Tab ==========

@Composable
internal fun NotificationsTab(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    openDialog: (SettingsDialog) -> Unit,
    listState: LazyListState,
) {
    val permission: NotificationPermissionCoordinator = koinInject()
    // Turning one of these on is the moment to ask for the notification permission, if it is missing.
    fun enabling(set: (Boolean) -> Unit): (Boolean) -> Unit = { on ->
        set(on)
        if (on) permission.onFeatureEnabled()
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
    ) {
        item(key = "notif_header") {
            SectionHeader(icon = Icons.Outlined.Notifications, title = "Reminders")
        }

        item(key = "notif_disabled_banner") {
            NotificationsDisabledBanner()
        }

        item(key = "notif_exact_alarm_banner") {
            ExactAlarmBanner()
        }

        if (state.supportsQuickAddTile) {
            item(key = "notif_quick_settings_header") {
                Text(
                    text = "Quick Settings",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }

            item(key = "notif_quick_add_tile") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Quick Add tile",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "Add a task from the notification shade",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    FilledTonalButton(onClick = viewModel::requestQuickAddTile) {
                        Text("Add tile")
                    }
                }
            }

            item(key = "notif_divider_quick_settings") {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
        }

        item(key = "notif_task_reminders") {
            SwitchRow(
                label = "Task Reminders",
                description = "Show notifications for task reminders",
                checked = state.notificationPrefs.taskRemindersEnabled,
                onCheckedChange = enabling(viewModel::setTaskRemindersEnabled),
            )
        }

        item(key = "notif_sound") {
            SwitchRow(
                label = "Sound",
                description = "Play sound with reminder notifications",
                checked = state.notificationPrefs.soundEnabled,
                onCheckedChange = viewModel::setSoundEnabled,
            )
        }

        item(key = "notif_divider_1") {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        item(key = "notif_daily_header") {
            Text(
                text = "Daily Summary",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
            )
        }

        item(key = "notif_daily_summary") {
            SwitchRow(
                label = "Enable Daily Summary",
                description = "Get a daily digest of upcoming tasks",
                checked = state.notificationPrefs.dailySummaryEnabled,
                onCheckedChange = enabling(viewModel::setDailySummaryEnabled),
            )
        }

        if (state.notificationPrefs.dailySummaryEnabled) {
            item(key = "notif_daily_time") {
                TimePickerRow(
                    label = "Summary Time",
                    hour = state.notificationPrefs.dailySummaryHour,
                    minute = state.notificationPrefs.dailySummaryMinute,
                    onClick = { openDialog(SettingsDialog.DailySummaryTime) },
                )
            }
        }

        item(key = "notif_afternoon_enabled") {
            SwitchRow(
                label = "Afternoon Summary",
                description = "A second daily digest in the afternoon",
                checked = state.notificationPrefs.afternoonSummaryEnabled,
                onCheckedChange = enabling(viewModel::setAfternoonSummaryEnabled),
            )
        }

        if (state.notificationPrefs.afternoonSummaryEnabled) {
            item(key = "notif_afternoon_time") {
                TimePickerRow(
                    label = "Afternoon Time",
                    hour = state.notificationPrefs.afternoonSummaryHour,
                    minute = state.notificationPrefs.afternoonSummaryMinute,
                    onClick = { openDialog(SettingsDialog.AfternoonSummaryTime) },
                )
            }
        }

        item(key = "notif_overdue") {
            SwitchRow(
                label = "Include Overdue",
                description = "Count overdue tasks in the summary",
                checked = state.notificationPrefs.notifyOverdueEnabled,
                onCheckedChange = viewModel::setNotifyOverdueEnabled,
            )
        }

        item(key = "notif_due_today") {
            SwitchRow(
                label = "Include Due Today",
                description = "Count tasks due today in the summary",
                checked = state.notificationPrefs.notifyDueTodayEnabled,
                onCheckedChange = viewModel::setNotifyDueTodayEnabled,
            )
        }

        item(key = "notif_upcoming") {
            SwitchRow(
                label = "Include Tomorrow",
                description = "Count tasks due tomorrow in the summary",
                checked = state.notificationPrefs.notifyUpcomingEnabled,
                onCheckedChange = viewModel::setNotifyUpcomingEnabled,
            )
        }

        item(key = "notif_divider_reminder") {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        item(key = "notif_reminder_header") {
            Text(
                text = "Default Reminder",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
            )
        }

        item(key = "notif_default_offset") {
            val offsetLabel = REMINDER_OFFSET_OPTIONS.firstOrNull {
                it.second == state.notificationPrefs.defaultReminderOffset
            }?.first ?: "None"
            SettingsValueRow(
                label = "Reminder Offset",
                value = offsetLabel,
                onClick = { openDialog(SettingsDialog.ReminderOffset) },
            )
        }

        if (state.notificationPrefs.defaultReminderOffset != 0) {
            item(key = "notif_default_relative") {
                val relativeLabel = REMINDER_RELATIVE_OPTIONS.firstOrNull {
                    it.second == state.notificationPrefs.defaultReminderRelativeTo
                }?.first ?: "Due date"
                SettingsValueRow(
                    label = "Relative To",
                    value = relativeLabel,
                    onClick = { openDialog(SettingsDialog.ReminderRelativeTo) },
                )
            }
        }

        item(key = "notif_divider_2") {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        item(key = "notif_test_header") {
            Text(
                text = "Testing",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
            )
        }

        item(key = "notif_test") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                FilledTonalButton(onClick = viewModel::sendTestNotification) {
                    Text("Send Test Notification")
                }
            }
        }

        item(key = "notif_bottom_spacer") {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
