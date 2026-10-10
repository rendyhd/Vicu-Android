package com.rendyhd.vicu.ui.screens.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rendyhd.vicu.ui.components.shared.VicuTopAppBar
import org.koin.compose.viewmodel.koinViewModel

/**
 * Settings: the tabs (General, Projects, Notifications, Gestures), their scroll positions and the
 * one open dialog. Everything that has to survive a rotation lives in saveable state here: the tab,
 * whether the archived projects are open, the open dialog (by id, see [SettingsDialog]) and each
 * tab's scroll. The tabs are in GeneralTab, ProjectsTab, NotificationsTab and GesturesTab; the
 * dialogs are drawn by [SettingsDialogHost].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenDrawer: () -> Unit = {},
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val useDeviceColors by viewModel.useDeviceColors.collectAsStateWithLifecycle()
    val routineHistoryCount by viewModel.routineHistoryCount.collectAsStateWithLifecycle()
    val customListChangesUnsynced by viewModel.customListChangesUnsynced.collectAsStateWithLifecycle()
    val projectRows by viewModel.projectRows.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    // The archived projects start closed each time Settings is opened (not a stored preference).
    var archivedProjectsExpanded by rememberSaveable { mutableStateOf(false) }
    var dialog by rememberSaveable(stateSaver = SettingsDialogSaver) { mutableStateOf<SettingsDialog?>(null) }

    // One scroll state per tab, so switching tabs does not lose the place in the one left.
    val generalListState = rememberLazyListState()
    val projectsListState = rememberLazyListState()
    val notificationsListState = rememberLazyListState()
    val gesturesListState = rememberLazyListState()

    // Custom completion-sound picker. Uses OpenDocument so we get a persistable
    // URI that survives reboots. The picker grants persistent read permission;
    // we re-claim it on result and stash the URI string in BehaviorPrefsStore.
    val context = LocalContext.current
    val pickSoundLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
                // Some pickers don't grant persistable permission. The URI may still
                // work for the current process; if not, the player will log and skip.
            }
            viewModel.setCompletionSoundUri(uri.toString())
        }
    }

    // Show snackbar messages
    LaunchedEffect(state.error, state.successMessage) {
        val msg = state.error ?: state.successMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearMessages()
        }
    }

    val tabTitles = listOf("General", "Projects", "Notifications", "Gestures")

    Scaffold(
        topBar = {
            VicuTopAppBar(
                title = { Text("Settings") },
                onOpenDrawer = onOpenDrawer,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Scrollable so the four tabs keep their full names on a narrow phone; no edge padding,
            // so they start at the edge and all fit at common widths.
            PrimaryScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 0.dp) {
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
                    useDeviceColors = useDeviceColors,
                    viewModel = viewModel,
                    openDialog = { dialog = it },
                    onPickCompletionSound = { pickSoundLauncher.launch(arrayOf("audio/*")) },
                    listState = generalListState,
                )
                1 -> ProjectsTab(
                    state = state,
                    projectRows = projectRows,
                    archivedExpanded = archivedProjectsExpanded,
                    onArchivedExpandedChange = { archivedProjectsExpanded = it },
                    viewModel = viewModel,
                    openDialog = { dialog = it },
                    listState = projectsListState,
                )
                2 -> NotificationsTab(
                    state = state,
                    viewModel = viewModel,
                    openDialog = { dialog = it },
                    listState = notificationsListState,
                )
                3 -> GesturesTab(
                    scheduleAction = state.behaviorPrefs.scheduleAction,
                    listState = gesturesListState,
                )
            }
        }
    }

    SettingsDialogHost(
        dialog = dialog,
        state = state,
        routineHistoryCount = routineHistoryCount,
        customListChangesUnsynced = customListChangesUnsynced,
        viewModel = viewModel,
        open = { dialog = it },
        dismiss = { dialog = null },
    )
}
