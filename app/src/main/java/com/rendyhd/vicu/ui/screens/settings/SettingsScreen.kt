package com.rendyhd.vicu.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.SwipeLeft
import androidx.compose.material.icons.outlined.SwipeRight
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.rendyhd.vicu.data.local.ThemeMode
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.ui.components.shared.CustomListDialog
import com.rendyhd.vicu.ui.components.shared.LabelEditDialog
import com.rendyhd.vicu.ui.components.shared.VicuTopAppBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenDrawer: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    // Dialog state
    var showLabelDialog by remember { mutableStateOf(false) }
    var editingLabel by remember { mutableStateOf<Label?>(null) }
    var deletingLabel by remember { mutableStateOf<Label?>(null) }
    var showCustomListDialog by remember { mutableStateOf(false) }
    var editingCustomList by remember { mutableStateOf<CustomList?>(null) }
    var deletingCustomList by remember { mutableStateOf<CustomList?>(null) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showInboxPicker by remember { mutableStateOf(false) }

    // Show snackbar messages
    LaunchedEffect(state.error, state.successMessage) {
        val msg = state.error ?: state.successMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearMessages()
        }
    }

    val tabTitles = listOf("General", "Notifications", "Gestures")

    Scaffold(
        topBar = {
            VicuTopAppBar(
                title = { Text("Settings") },
                onOpenDrawer = onOpenDrawer,
                onNavigateToSearch = onNavigateToSearch,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) },
                    )
                }
            }

            when (selectedTab) {
                0 -> GeneralTab(
                    state = state,
                    onThemeChange = viewModel::setThemeMode,
                    onShowInboxPicker = { showInboxPicker = true },
                    onShowLabelDialog = { showLabelDialog = true },
                    onEditLabel = { label ->
                        editingLabel = label
                        showLabelDialog = true
                    },
                    onDeleteLabel = { deletingLabel = it },
                    onShowCustomListDialog = { showCustomListDialog = true },
                    onEditCustomList = { list ->
                        editingCustomList = list
                        showCustomListDialog = true
                    },
                    onDeleteCustomList = { deletingCustomList = it },
                    onTriggerSync = viewModel::triggerSync,
                    onRetryFailed = viewModel::retryFailedActions,
                    onClearFailed = viewModel::clearFailedActions,
                    onClearCache = { showClearCacheDialog = true },
                    onLogout = { showLogoutDialog = true },
                )
                1 -> NotificationsTab(
                    state = state,
                    onTaskRemindersChanged = viewModel::setTaskRemindersEnabled,
                    onSoundChanged = viewModel::setSoundEnabled,
                    onDailySummaryChanged = viewModel::setDailySummaryEnabled,
                    onShowTimePicker = { showTimePicker = true },
                    onSendTest = viewModel::sendTestNotification,
                )
                2 -> GesturesTab()
            }
        }
    }

    // === Dialogs ===

    // Time picker dialog
    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = state.notificationPrefs.dailySummaryHour,
            initialMinute = state.notificationPrefs.dailySummaryMinute,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Daily Summary Time") },
            text = {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    TimePicker(state = timePickerState)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setDailySummaryTime(timePickerState.hour, timePickerState.minute)
                    showTimePicker = false
                }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Label create/edit dialog
    if (showLabelDialog) {
        LabelEditDialog(
            label = editingLabel,
            onSave = { name, hexColor ->
                if (editingLabel != null) {
                    viewModel.updateLabel(editingLabel!!, name, hexColor)
                } else {
                    viewModel.createLabel(name, hexColor)
                }
                showLabelDialog = false
                editingLabel = null
            },
            onDismiss = {
                showLabelDialog = false
                editingLabel = null
            },
        )
    }

    // Label delete confirmation
    if (deletingLabel != null) {
        AlertDialog(
            onDismissRequest = { deletingLabel = null },
            title = { Text("Delete Label") },
            text = { Text("Delete \"${deletingLabel!!.title}\"? It will be removed from all tasks.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteLabel(deletingLabel!!.id)
                    deletingLabel = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingLabel = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Custom list create/edit dialog
    if (showCustomListDialog) {
        CustomListDialog(
            customList = editingCustomList,
            projects = state.projects,
            labels = state.labels,
            onSave = { list ->
                viewModel.saveCustomList(list)
                showCustomListDialog = false
                editingCustomList = null
            },
            onDismiss = {
                showCustomListDialog = false
                editingCustomList = null
            },
        )
    }

    // Custom list delete confirmation
    if (deletingCustomList != null) {
        AlertDialog(
            onDismissRequest = { deletingCustomList = null },
            title = { Text("Delete List") },
            text = { Text("Delete \"${deletingCustomList!!.name}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteCustomList(deletingCustomList!!.id)
                    deletingCustomList = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingCustomList = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Logout confirmation
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Sign Out") },
            text = { Text("Sign out of your Vikunja account? All local data will be cleared.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.logout()
                    showLogoutDialog = false
                }) {
                    Text("Sign Out", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Clear cache confirmation
    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text("Clear Cache") },
            text = { Text("Clear all cached data and re-sync from the server? You will not be signed out.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearCacheAndResync()
                    showClearCacheDialog = false
                }) {
                    Text("Clear & Sync")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Inbox project picker
    if (showInboxPicker) {
        AlertDialog(
            onDismissRequest = { showInboxPicker = false },
            title = { Text("Select Inbox Project") },
            text = {
                LazyColumn {
                    items(state.projects, key = { it.id }) { project ->
                        val isSelected = project.id == state.inboxProjectId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setInboxProject(project.id)
                                    showInboxPicker = false
                                }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = project.title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            if (isSelected) {
                                Icon(
                                    Icons.Outlined.CheckCircle,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showInboxPicker = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

// ========== General Tab ==========

@Composable
private fun GeneralTab(
    state: SettingsUiState,
    onThemeChange: (ThemeMode) -> Unit,
    onShowInboxPicker: () -> Unit,
    onShowLabelDialog: () -> Unit,
    onEditLabel: (Label) -> Unit,
    onDeleteLabel: (Label) -> Unit,
    onShowCustomListDialog: () -> Unit,
    onEditCustomList: (CustomList) -> Unit,
    onDeleteCustomList: (CustomList) -> Unit,
    onTriggerSync: () -> Unit,
    onRetryFailed: () -> Unit,
    onClearFailed: () -> Unit,
    onClearCache: () -> Unit,
    onLogout: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
    ) {
        // === Account section ===
        item(key = "account_header") {
            SectionHeader(icon = Icons.Outlined.Person, title = "Account")
        }

        item(key = "account_info") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                if (state.username.isNotBlank()) {
                    InfoRow(label = "Username", value = state.username)
                }
                if (state.email.isNotBlank()) {
                    InfoRow(label = "Email", value = state.email)
                }
                InfoRow(
                    label = "Auth method",
                    value = when (state.authMethod) {
                        "oidc" -> "Signed in via SSO"
                        "password" -> "Password"
                        "api_token" -> "API Token"
                        else -> state.authMethod.ifBlank { "Unknown" }
                    },
                )
                if (state.vikunjaUrl.isNotBlank()) {
                    InfoRow(label = "Server", value = state.vikunjaUrl)
                }
            }
        }

        item(key = "inbox_project") {
            val inboxName = state.projects.find { it.id == state.inboxProjectId }?.title ?: "Not set"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onShowInboxPicker)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Inbox Project",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = inboxName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        item(key = "account_divider") {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        // === Appearance section ===
        item(key = "theme_header") {
            Text(
                text = "Appearance",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
            )
        }

        item(key = "theme_picker") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "Theme",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(modifier = Modifier.height(8.dp))
                @OptIn(ExperimentalMaterial3Api::class)
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val options = listOf(
                        ThemeMode.System to "System",
                        ThemeMode.Light to "Light",
                        ThemeMode.Dark to "Dark",
                    )
                    options.forEachIndexed { index, (mode, label) ->
                        SegmentedButton(
                            selected = state.themeMode == mode,
                            onClick = { onThemeChange(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                        ) {
                            Text(label)
                        }
                    }
                }
            }
        }

        item(key = "theme_divider") {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        // === Labels section ===
        item(key = "labels_header") {
            SectionTitle(
                title = "Labels",
                onAdd = onShowLabelDialog,
            )
        }

        if (state.labels.isEmpty()) {
            item(key = "labels_empty") {
                Text(
                    text = "No labels yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        } else {
            items(state.labels, key = { "label_${it.id}" }) { label ->
                LabelRow(
                    label = label,
                    onEdit = { onEditLabel(label) },
                    onDelete = { onDeleteLabel(label) },
                )
            }
        }

        item(key = "labels_divider") {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        // === Custom Lists section ===
        item(key = "lists_header") {
            SectionTitle(
                title = "Custom Lists",
                onAdd = onShowCustomListDialog,
            )
        }

        if (state.customLists.isEmpty()) {
            item(key = "lists_empty") {
                Text(
                    text = "No custom lists yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        } else {
            items(state.customLists, key = { "list_${it.id}" }) { list ->
                CustomListRow(
                    customList = list,
                    onEdit = { onEditCustomList(list) },
                    onDelete = { onDeleteCustomList(list) },
                )
            }
        }

        item(key = "lists_divider") {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        // === Data section ===
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
                            text = "${state.pendingActionCount} pending change(s)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.failedActionCount > 0) {
                        Text(
                            text = "${state.failedActionCount} failed change(s)",
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
                    onClick = onTriggerSync,
                    enabled = state.isOnline,
                ) {
                    Text("Sync Now")
                }
                if (state.failedActionCount > 0) {
                    FilledTonalButton(onClick = onRetryFailed) {
                        Text("Retry All")
                    }
                    TextButton(onClick = onClearFailed) {
                        Text("Clear Failed")
                    }
                }
            }
        }

        item(key = "clear_cache") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClearCache)
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

        item(key = "data_divider") {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        // === Sign Out ===
        item(key = "logout") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onLogout)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.ExitToApp,
                    contentDescription = "Sign Out",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Sign Out",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        item(key = "general_bottom_spacer") {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ========== Notifications Tab ==========

@Composable
private fun NotificationsTab(
    state: SettingsUiState,
    onTaskRemindersChanged: (Boolean) -> Unit,
    onSoundChanged: (Boolean) -> Unit,
    onDailySummaryChanged: (Boolean) -> Unit,
    onShowTimePicker: () -> Unit,
    onSendTest: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "notif_header") {
            SectionHeader(icon = Icons.Outlined.Notifications, title = "Reminders")
        }

        item(key = "notif_task_reminders") {
            SwitchRow(
                label = "Task Reminders",
                description = "Show notifications for task reminders",
                checked = state.notificationPrefs.taskRemindersEnabled,
                onCheckedChange = onTaskRemindersChanged,
            )
        }

        item(key = "notif_sound") {
            SwitchRow(
                label = "Sound",
                description = "Play sound with reminder notifications",
                checked = state.notificationPrefs.soundEnabled,
                onCheckedChange = onSoundChanged,
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
                onCheckedChange = onDailySummaryChanged,
            )
        }

        if (state.notificationPrefs.dailySummaryEnabled) {
            item(key = "notif_daily_time") {
                TimePickerRow(
                    label = "Summary Time",
                    hour = state.notificationPrefs.dailySummaryHour,
                    minute = state.notificationPrefs.dailySummaryMinute,
                    onClick = onShowTimePicker,
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
                FilledTonalButton(onClick = onSendTest) {
                    Text("Send Test Notification")
                }
            }
        }

        item(key = "notif_bottom_spacer") {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ========== Gestures Tab ==========

@Composable
private fun GesturesTab() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "gestures_header") {
            SectionHeader(icon = Icons.Outlined.Gesture, title = "Gesture Guide")
        }

        item(key = "gestures_intro") {
            Text(
                text = "Learn how to interact with tasks using gestures.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        item(key = "gesture_swipe_right") {
            GestureRow(
                icon = Icons.Outlined.SwipeRight,
                gesture = "Swipe Right",
                description = "Complete a task",
                color = Color(0xFF4CAF50),
            )
        }

        item(key = "gesture_swipe_left") {
            GestureRow(
                icon = Icons.Outlined.SwipeLeft,
                gesture = "Swipe Left",
                description = "Schedule a task (set due date)",
                color = Color(0xFFFF9800),
            )
        }

        item(key = "gesture_tap") {
            GestureRow(
                icon = Icons.Outlined.TouchApp,
                gesture = "Tap",
                description = "Open task details",
                color = MaterialTheme.colorScheme.primary,
            )
        }

        item(key = "gesture_long_press") {
            GestureRow(
                icon = Icons.Outlined.TouchApp,
                gesture = "Long Press",
                description = "Select multiple tasks",
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        item(key = "gesture_fab") {
            GestureRow(
                icon = Icons.Filled.Add,
                gesture = "FAB (+)",
                description = "Create a new task",
                color = MaterialTheme.colorScheme.primary,
            )
        }

        item(key = "gesture_checkbox") {
            GestureRow(
                icon = Icons.Outlined.CheckCircle,
                gesture = "Tap Checkbox",
                description = "Toggle task completion",
                color = Color(0xFF4CAF50),
            )
        }

        item(key = "gestures_divider") {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        item(key = "gestures_tip") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Tip: After completing a task, an undo option appears briefly in case you want to revert it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item(key = "gestures_bottom_spacer") {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ========== Shared composables ==========

@Composable
private fun SectionHeader(
    icon: ImageVector,
    title: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

@Composable
private fun GestureRow(
    icon: ImageVector,
    gesture: String,
    description: String,
    color: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(color.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                text = gesture,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun TimePickerRow(
    label: String,
    hour: Int,
    minute: Int,
    onClick: () -> Unit,
) {
    val timeText = String.format(
        "%d:%02d %s",
        if (hour % 12 == 0) 12 else hour % 12,
        minute,
        if (hour < 12) "AM" else "PM",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = timeText,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SectionTitle(
    title: String,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 16.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        IconButton(onClick = onAdd) {
            Icon(
                Icons.Default.Add,
                contentDescription = "Add",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun LabelRow(
    label: Label,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val dotColor = parseHexColor(label.hexColor)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(dotColor ?: MaterialTheme.colorScheme.onSurfaceVariant),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label.title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Edit,
                contentDescription = "Edit",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun CustomListRow(
    customList: CustomList,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.FilterList,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = customList.name,
                style = MaterialTheme.typography.bodyLarge,
            )
            val filterSummary = buildFilterSummary(customList)
            if (filterSummary.isNotBlank()) {
                Text(
                    text = filterSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Edit,
                contentDescription = "Edit",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun buildFilterSummary(list: CustomList): String {
    val parts = mutableListOf<String>()
    val f = list.filter
    if (f.dueDateFilter != "all") {
        parts.add(f.dueDateFilter.replace("_", " "))
    }
    if (f.projectIds.isNotEmpty()) {
        parts.add("${f.projectIds.size} project(s)")
    }
    if (f.labelIds.isNotEmpty()) {
        parts.add("${f.labelIds.size} label(s)")
    }
    if (f.includeDone) {
        parts.add("incl. done")
    }
    return parts.joinToString(" · ")
}

private fun parseHexColor(hex: String): Color? {
    if (hex.isBlank()) return null
    return try {
        val normalized = if (hex.startsWith("#")) hex else "#$hex"
        Color(android.graphics.Color.parseColor(normalized))
    } catch (_: Exception) {
        null
    }
}
