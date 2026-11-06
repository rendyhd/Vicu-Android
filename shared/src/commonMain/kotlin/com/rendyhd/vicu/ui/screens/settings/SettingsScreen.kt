package com.rendyhd.vicu.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rendyhd.vicu.ui.components.shared.VicuSnackbarHost
import com.rendyhd.vicu.ui.rememberCompletionSoundPicker
import com.rendyhd.vicu.ui.components.shared.VicuTopAppBar
import org.koin.compose.viewmodel.koinViewModel

/**
 * Settings: the tabs (General, Projects, Notifications, Gestures, Assistant), their scroll positions and the
 * one open dialog. Everything that has to survive a rotation lives in saveable state here: the tab,
 * whether the archived projects are open, the open dialog (by id, see [SettingsDialog]) and each
 * tab's scroll. The tabs are in GeneralTab, ProjectsTab, NotificationsTab and GesturesTab; the
 * dialogs are drawn by [SettingsDialogHost].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenDrawer: () -> Unit = {},
    /** Opens Ask (Try it), with the microphone on for Talk. */
    onOpenAsk: (talk: Boolean) -> Unit = {},
    viewModel: SettingsViewModel = koinViewModel(),
    assistantViewModel: AssistantSettingsViewModel = koinViewModel(),
) {
    val assistantState by assistantViewModel.uiState.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val useDeviceColors by viewModel.useDeviceColors.collectAsStateWithLifecycle()
    val pureBlack by viewModel.pureBlack.collectAsStateWithLifecycle()
    val routineHistoryCount by viewModel.routineHistoryCount.collectAsStateWithLifecycle()
    val syncedDataUnsynced by viewModel.syncedDataUnsynced.collectAsStateWithLifecycle()
    val projectItems by viewModel.projectItems.collectAsStateWithLifecycle()
    val projectCategories by viewModel.categories.collectAsStateWithLifecycle()
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
    val assistantListState = rememberLazyListState()

    // Custom completion-sound picker; the platform keeps access to the file across restarts.
    val pickCompletionSound = rememberCompletionSoundPicker { viewModel.setCompletionSoundUri(it) }

    // Show snackbar messages
    LaunchedEffect(state.error, state.successMessage) {
        val msg = state.error ?: state.successMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearMessages()
        }
    }

    AssistantMessages(assistantState, assistantViewModel) { snackbarHostState.showSnackbar(it) }

    val tabTitles = listOf("General", "Projects", "Notifications", "Gestures", "Assistant")

    Scaffold(
        topBar = {
            VicuTopAppBar(
                title = { Text("Settings") },
                onOpenDrawer = onOpenDrawer,
            )
        },
        snackbarHost = { VicuSnackbarHost(snackbarHostState) },
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
                    pureBlack = pureBlack,
                    viewModel = viewModel,
                    openDialog = { dialog = it },
                    onPickCompletionSound = pickCompletionSound,
                    listState = generalListState,
                )
                1 -> ProjectsTab(
                    state = state,
                    projectItems = projectItems,
                    categories = projectCategories,
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
                    swipeRight = state.behaviorPrefs.swipeRight,
                    swipeLeft = state.behaviorPrefs.swipeLeft,
                    onSwipeRightChange = viewModel::setSwipeRight,
                    onSwipeLeftChange = viewModel::setSwipeLeft,
                    listState = gesturesListState,
                )
                4 -> AssistantTab(
                    state = assistantState,
                    viewModel = assistantViewModel,
                    listState = assistantListState,
                    onOpenAsk = onOpenAsk,
                )
            }
        }
    }

    SettingsDialogHost(
        dialog = dialog,
        state = state,
        routineHistoryCount = routineHistoryCount,
        syncedDataUnsynced = syncedDataUnsynced,
        viewModel = viewModel,
        open = { dialog = it },
        dismiss = { dialog = null },
    )
}
