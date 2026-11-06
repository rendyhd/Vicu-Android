package com.rendyhd.vicu.ui

import com.rendyhd.vicu.ui.components.category.LocalProjectCategories
import com.rendyhd.vicu.ui.components.shared.VicuSnackbar
import com.rendyhd.vicu.ui.components.shared.snackbarColors
import com.rendyhd.vicu.util.Logger
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.rendyhd.vicu.auth.AuthDebugLog
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.auth.AuthState
import com.rendyhd.vicu.data.local.BehaviorPrefs
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.SwipeAction
import com.rendyhd.vicu.data.local.TempIdGenerator
import com.rendyhd.vicu.domain.model.BottomBarSlot
import com.rendyhd.vicu.domain.model.BottomBarSlotType
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.SharedContent
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.permission.NotificationPermissionCoordinator
import com.rendyhd.vicu.ui.components.picker.WhenSheet
import com.rendyhd.vicu.ui.components.settings.NotificationPermissionSheet
import com.rendyhd.vicu.ui.components.fab.FabController
import com.rendyhd.vicu.ui.components.fab.FabHint
import com.rendyhd.vicu.ui.components.fab.FabOverlays
import com.rendyhd.vicu.ui.components.fab.LocalFabDropTargets
import com.rendyhd.vicu.ui.components.shared.AppFab
import com.rendyhd.vicu.ui.components.shared.AppFabHost
import com.rendyhd.vicu.ui.components.shared.CustomListDialog
import com.rendyhd.vicu.ui.components.shared.LocalAppFabHost
import com.rendyhd.vicu.ui.components.shared.ProjectEditDialog
import com.rendyhd.vicu.ui.components.shared.IconRegistry
import com.rendyhd.vicu.ui.components.shared.LocalFabAlignStart
import com.rendyhd.vicu.ui.components.shared.LocalClockDay
import com.rendyhd.vicu.ui.components.shared.LocalDateFormat
import com.rendyhd.vicu.ui.components.shared.LocalIs24Hour
import com.rendyhd.vicu.ui.components.shared.LocalOpenAsk
import com.rendyhd.vicu.ui.components.shared.LocalToday
import com.rendyhd.vicu.ui.components.shared.rememberIs24HourFormat
import com.rendyhd.vicu.ui.components.shared.FailedActionsBanner
import com.rendyhd.vicu.ui.components.shared.OfflineBanner
import com.rendyhd.vicu.ui.components.shared.SmartListIdentity
import com.rendyhd.vicu.ui.theme.LocalVicuColors
import com.rendyhd.vicu.domain.repository.QuickDue
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.ui.components.picker.ProjectPickerDialog
import com.rendyhd.vicu.ui.components.task.DeleteTaskDialog
import com.rendyhd.vicu.ui.components.task.LocalTaskRowActions
import com.rendyhd.vicu.util.descendantsDepthFirst
import com.rendyhd.vicu.ui.components.task.TaskEntrySheet
import com.rendyhd.vicu.ui.components.task.TaskRowActions
import com.rendyhd.vicu.ui.components.task.LocalRowDensity
import com.rendyhd.vicu.ui.components.task.LocalSubtaskDisplayMode
import com.rendyhd.vicu.ui.navigation.AnytimeRoute
import com.rendyhd.vicu.ui.navigation.AskRoute
import com.rendyhd.vicu.ui.navigation.AppNavHost
import com.rendyhd.vicu.ui.navigation.CustomListRoute
import com.rendyhd.vicu.ui.navigation.DrawerContent
import com.rendyhd.vicu.ui.navigation.DrawerViewModel
import com.rendyhd.vicu.ui.navigation.InboxRoute
import com.rendyhd.vicu.ui.navigation.LogbookRoute
import com.rendyhd.vicu.ui.navigation.NavigationTicker
import com.rendyhd.vicu.ui.navigation.ProjectRoute
import com.rendyhd.vicu.ui.navigation.GuidedReviewRoute
import com.rendyhd.vicu.ui.navigation.GuidedSortRoute
import com.rendyhd.vicu.ui.navigation.PlanMyDayRoute
import com.rendyhd.vicu.ui.navigation.ReviewRoute
import com.rendyhd.vicu.ui.navigation.WeeklyReviewRoute
import com.rendyhd.vicu.ui.navigation.openGuided
import com.rendyhd.vicu.ui.navigation.RoutinesRoute
import com.rendyhd.vicu.ui.navigation.SearchRoute
import com.rendyhd.vicu.ui.navigation.SettingsRoute
import com.rendyhd.vicu.ui.navigation.SetupRoute
import com.rendyhd.vicu.ui.navigation.TagRoute
import com.rendyhd.vicu.ui.navigation.SharedContentSaver
import com.rendyhd.vicu.ui.navigation.TodayRoute
import com.rendyhd.vicu.ui.navigation.PlanRoute
import com.rendyhd.vicu.ui.navigation.UpcomingRoute
import com.rendyhd.vicu.ui.navigation.ViewTarget
import com.rendyhd.vicu.ui.navigation.awaitGraph
import com.rendyhd.vicu.ui.navigation.currentRouteKey
import com.rendyhd.vicu.ui.navigation.navigateTopLevel
import com.rendyhd.vicu.ui.navigation.routeKey
import com.rendyhd.vicu.ui.navigation.startDestinationFor
import com.rendyhd.vicu.ui.navigation.toRoute
import com.rendyhd.vicu.ui.screens.taskdetail.EditorBackState
import com.rendyhd.vicu.ui.components.collab.CollabUi
import com.rendyhd.vicu.ui.components.collab.CollabViewModel
import com.rendyhd.vicu.ui.components.collab.LocalCollab
import com.rendyhd.vicu.ui.screens.comments.TaskCommentsScreen
import com.rendyhd.vicu.ui.screens.comments.TaskCommentsViewModel
import com.rendyhd.vicu.ui.screens.taskdetail.EditorOverlay
import com.rendyhd.vicu.ui.screens.taskdetail.TaskDetailScreen
import com.rendyhd.vicu.ui.screens.taskdetail.TaskDetailViewModel
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.ui.screens.ask.AskEntry
import com.rendyhd.vicu.ui.screens.ask.AskSheet
import com.rendyhd.vicu.ui.screens.ask.AskViewModel
import com.rendyhd.vicu.voice.HoldToTalk
import com.rendyhd.vicu.voice.TaskDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import com.rendyhd.vicu.ai.AiSettings
import com.rendyhd.vicu.ai.AiSettingsStore
import com.rendyhd.vicu.ai.ask.AskList
import com.rendyhd.vicu.ai.dictation.DictationController
import com.rendyhd.vicu.ai.dictation.DictationMic
import com.rendyhd.vicu.ui.screens.dictation.DictationSheet
import com.rendyhd.vicu.util.Constants
import com.rendyhd.vicu.util.DateDisplayFormat
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.parseHexColor
import com.rendyhd.vicu.util.postIfRefused
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** An app message on the snackbar; [openTaskId] puts Open next to its action. */
private class AppSnackbarVisuals(
    override val message: String,
    override val actionLabel: String?,
    override val duration: SnackbarDuration,
    val openTaskId: Long?,
) : SnackbarVisuals {
    override val withDismissAction: Boolean get() = false
}

/** The standard snackbar has room for one action; this one has Open and the message's own. */
@Composable
private fun SnackbarWithOpen(data: SnackbarData, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme.snackbarColors()
    val buttons = ButtonDefaults.textButtonColors(contentColor = colors.action)
    Snackbar(
        modifier = Modifier.padding(12.dp),
        // Beside the text, unless large text leaves it a word per line there.
        actionOnNewLine = LocalDensity.current.fontScale > 1.3f,
        containerColor = colors.container,
        contentColor = colors.content,
        actionContentColor = colors.action,
        action = {
            Row {
                TextButton(
                    colors = buttons,
                    onClick = {
                        data.dismiss()
                        onOpen()
                    },
                ) { Text("Open") }
                data.visuals.actionLabel?.let { label ->
                    TextButton(colors = buttons, onClick = data::performAction) { Text(label) }
                }
            }
        },
    ) {
        Text(data.visuals.message)
    }
}

internal data class BottomNavItem(
    val label: String,
    val icon: ImageVector,
    val route: Any,
    val routeName: String,
    val isParameterized: Boolean = false,
    /** Set for a smart list while smart list colours are on: its icon is drawn in the identity colour. */
    val identity: SmartListIdentity? = null,
    /** The colour the user gave a project or custom list slot; null draws the icon in the bar's content colour. */
    val color: Color? = null,
)

/** Carries the task editor's unsaved draft through process death (see [TaskDetailViewModel.currentDraftJson]). */
private class TaskDetailDraftHolder(val restored: String?)

/**
 * The bottom bar: the Inbox first, always, then the slots the user chose. A slot for something
 * that is gone (a deleted project or list) is left out, and so is a project slot for the Inbox
 * ([inboxProjectId]): the bar already starts with it.
 *
 * The smart lists wear their identity colours unless [smartListColors] is off; a project or
 * custom list slot wears the colour chosen for it, if any.
 */
internal fun resolveBottomBarItems(
    slots: List<BottomBarSlot>,
    allProjects: List<Project>,
    customLists: List<CustomList>,
    inboxProjectId: Long,
    smartListColors: Boolean = true,
    plannerEnabled: Boolean = false,
): List<BottomNavItem> {
    fun identity(of: SmartListIdentity) = of.takeIf { smartListColors }
    val items = mutableListOf(
        BottomNavItem("Inbox", SmartListIdentity.INBOX.icon, InboxRoute, "InboxRoute", identity = identity(SmartListIdentity.INBOX)),
    )
    for (slot in slots) {
        val item = when (slot.type) {
            BottomBarSlotType.TODAY -> BottomNavItem(
                "Today", IconRegistry.resolveIcon(slot), TodayRoute, "TodayRoute",
                identity = identity(SmartListIdentity.TODAY),
            )
            BottomBarSlotType.PLAN -> {
                // Off means no Plan entry (contract 16.9); the slot comes back when it is turned on.
                if (!plannerEnabled) continue
                BottomNavItem(
                    "Plan", IconRegistry.resolveIcon(slot), PlanRoute, "PlanRoute",
                    identity = identity(SmartListIdentity.PLAN),
                )
            }
            BottomBarSlotType.UPCOMING -> BottomNavItem(
                "Upcoming", IconRegistry.resolveIcon(slot), UpcomingRoute, "UpcomingRoute",
                identity = identity(SmartListIdentity.UPCOMING),
            )
            BottomBarSlotType.ANYTIME -> BottomNavItem(
                "Anytime", IconRegistry.resolveIcon(slot), AnytimeRoute, "AnytimeRoute",
                identity = identity(SmartListIdentity.ANYTIME),
            )
            BottomBarSlotType.PROJECT -> {
                val projectId = slot.referenceId.toLongOrNull() ?: continue
                if (projectId == inboxProjectId) continue
                val project = allProjects.find { it.id == projectId } ?: continue
                BottomNavItem(
                    project.title, IconRegistry.resolveIcon(slot),
                    ProjectRoute(projectId), "ProjectRoute/$projectId",
                    isParameterized = true,
                    color = parseHexColor(slot.colorHex),
                )
            }
            BottomBarSlotType.CUSTOM_LIST -> {
                val list = customLists.find { it.id == slot.referenceId } ?: continue
                BottomNavItem(
                    list.name, IconRegistry.resolveIcon(slot),
                    CustomListRoute(slot.referenceId), "CustomListRoute/${slot.referenceId}",
                    isParameterized = true,
                    color = parseHexColor(slot.colorHex),
                )
            }
        }
        items.add(item)
    }
    return items
}

@Composable
fun VicuApp(
    authManager: AuthManager,
    initialTaskId: kotlinx.coroutines.flow.StateFlow<Long?>? = null,
    onInitialTaskConsumed: () -> Unit = {},
    showTaskEntry: kotlinx.coroutines.flow.StateFlow<Boolean>? = null,
    showTaskEntryProjectId: kotlinx.coroutines.flow.StateFlow<Long?>? = null,
    onShowTaskEntryConsumed: () -> Unit = {},
    navigateToView: kotlinx.coroutines.flow.StateFlow<ViewTarget?>? = null,
    onNavigateToViewConsumed: () -> Unit = {},
    sharedContent: kotlinx.coroutines.flow.StateFlow<SharedContent?>? = null,
    onSharedContentConsumed: () -> Unit = {},
    startDictation: kotlinx.coroutines.flow.StateFlow<Boolean>? = null,
    onStartDictationConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    // Each move to another destination tells the screens' view models, so a multi-selection ends
    // with the list it was made in. The entry shown at start (or after a rotation) is not a move.
    val navigationTicker: NavigationTicker = koinInject()
    val notificationPermission: NotificationPermissionCoordinator = koinInject()
    LaunchedEffect(navController) {
        navController.currentBackStackEntryFlow
            .map { it.id }
            .distinctUntilChanged()
            .drop(1)
            .collect { navigationTicker.navigated() }
    }
    val currentDestination = navBackStackEntry?.destination
    val authState by authManager.authState.collectAsStateWithLifecycle()
    val aiSettingsStore: AiSettingsStore = koinInject()
    val aiSettings by aiSettingsStore.settings.collectAsStateWithLifecycle(initialValue = AiSettings())
    val dictationController: DictationController = koinInject()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Sheet state
    var showTaskEntrySheet by rememberSaveable { mutableStateOf(false) }
    var taskEntryDefaultProjectId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showTaskDetailSheet by rememberSaveable { mutableStateOf(false) }
    var taskDetailTaskId by rememberSaveable { mutableLongStateOf(0L) }
    var showNewListDialog by rememberSaveable { mutableStateOf(false) }
    // The task whose Comments screen is open over everything (0 when none); saved like the editor.
    var commentsTaskId by rememberSaveable { mutableLongStateOf(0L) }
    var showNewProjectDialog by rememberSaveable { mutableStateOf(false) }
    // Saved with the instance state: a rotation reopens the sheet with the shared text and files.
    var pendingSharedContent by rememberSaveable(stateSaver = SharedContentSaver) {
        mutableStateOf<SharedContent?>(null)
    }
    val taskDetailViewModel: TaskDetailViewModel = koinViewModel()
    val taskDetailUiState by taskDetailViewModel.uiState.collectAsStateWithLifecycle()

    // Unsaved edits in the task editor are written into the saved instance state when the
    // activity saves it (the saver runs then, so it is always current) and handed back to the
    // view model if the process was recreated. A view model that survived (rotation) ignores it.
    val taskDetailDraft = rememberSaveable(
        saver = Saver<TaskDetailDraftHolder, String>(
            save = { taskDetailViewModel.currentDraftJson() },
            restore = { TaskDetailDraftHolder(it) },
        ),
    ) { TaskDetailDraftHolder(null) }
    remember(taskDetailDraft) { taskDetailViewModel.restoreDraft(taskDetailDraft.restored) }

    // The current day follows midnight, resume and date/time-zone changes (the receiver for those
    // lives in the Application). Read through LocalToday so day-dependent labels recompose.
    val dayClock: DayClock = koinInject()
    val clockDay by dayClock.day.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { dayClock.refresh() }
    val is24Hour = rememberIs24HourFormat()
    val dateFormat = remember(is24Hour) { DateDisplayFormat.system(is24Hour) }

    // What a screen reader can do to a task row besides open it: the swipe gestures have no
    // equivalent for it, so the rows offer the quick due dates as actions.
    val taskRepository: TaskRepository = koinInject()
    // Messages for outcomes nobody is looking at (an autosave that failed after the editor
    // closed). Shown in a snackbar above everything, including the full-screen editor.
    val appMessages: AppMessages = koinInject()
    // The swipe actions the user chose (Settings, Gestures). Choose when, Move and Delete open their
    // sheet or dialog here, over whatever screen the row is on; the others write at once.
    val behaviorPrefsStore: BehaviorPrefsStore = koinInject()
    val behaviorPrefs by behaviorPrefsStore.getPrefs().collectAsStateWithLifecycle(initialValue = BehaviorPrefs())
    val swipeRightPref by rememberUpdatedState(behaviorPrefs.swipeRight)
    val swipeLeftPref by rememberUpdatedState(behaviorPrefs.swipeLeft)
    val confirmBeforeDelete by rememberUpdatedState(behaviorPrefs.confirmBeforeDelete)
    var whenSheetTask by remember { mutableStateOf<Task?>(null) }
    var moveSheetTask by remember { mutableStateOf<Task?>(null) }
    var deleteDialogTask by remember { mutableStateOf<Task?>(null) }
    // Deletes the stored task; its subtasks go too unless [deleteSubtasks] is false.
    fun deleteSwiped(taskId: Long, deleteSubtasks: Boolean) {
        scope.launch { appMessages.postIfRefused(taskRepository.delete(taskId, deleteSubtasks), "delete the task") }
    }
    val taskRowActions = remember(taskRepository) {
        object : TaskRowActions {
            override fun scheduleDue(taskId: Long, due: QuickDue) {
                scope.launch { appMessages.postIfRefused(taskRepository.scheduleDue(taskId, due), "schedule the task") }
            }

            override val swipeRight: SwipeAction get() = swipeRightPref

            override val swipeLeft: SwipeAction get() = swipeLeftPref

            override fun swipe(taskId: Long, action: SwipeAction) {
                scope.launch {
                    when (action) {
                        SwipeAction.CHOOSE_WHEN -> whenSheetTask = taskRepository.getById(taskId).first()
                        SwipeAction.DUE_TODAY -> scheduleDue(taskId, QuickDue.TODAY)
                        SwipeAction.DUE_TOMORROW -> scheduleDue(taskId, QuickDue.TOMORROW)
                        SwipeAction.URGENT ->
                            appMessages.postIfRefused(taskRepository.setPriority(taskId, 4), "mark the task urgent")
                        SwipeAction.MOVE -> moveSheetTask = taskRepository.getById(taskId).first()
                        SwipeAction.DELETE -> {
                            val task = taskRepository.getById(taskId).first() ?: return@launch
                            // A task with subtasks always asks, as the editor's delete does.
                            if (confirmBeforeDelete || task.descendantsDepthFirst().isNotEmpty()) {
                                deleteDialogTask = task
                            } else {
                                deleteSwiped(task.id, deleteSubtasks = true)
                            }
                        }
                        // The row completes the task itself; Nothing never swipes.
                        SwipeAction.COMPLETE, SwipeAction.NONE -> Unit
                    }
                }
            }
        }
    }
    // Moves the stored task and, as the editor does, its subtasks (Vikunja does not cascade a move).
    fun moveSwiped(task: Task, projectId: Long) {
        if (projectId == task.projectId) return
        scope.launch {
            val moved = taskRepository.moveToProject(task.id, projectId)
            if (appMessages.postIfRefused(moved, "move the task")) return@launch
            if (task.descendantsDepthFirst().isNotEmpty()) {
                appMessages.postIfRefused(taskRepository.moveDescendantsToProject(task.id, projectId), "move the subtasks")
            }
        }
    }
    // Only the due date is written, on the task as it is stored now.
    fun setWhenDue(taskId: Long, dueDate: String) {
        scope.launch {
            val current = taskRepository.getById(taskId).first()
            if (current == null) {
                appMessages.post("Could not schedule the task: it is no longer available")
                return@launch
            }
            appMessages.postIfRefused(taskRepository.update(current.copy(dueDate = dueDate)), "schedule the task")
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val tempIds = koinInject<TempIdGenerator>()
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(appMessages) {
        appMessages.messages.collectLatest { message ->
            val millis = message.durationMillis
            val result = if (millis == null) {
                snackbarHostState.showSnackbar(
                    AppSnackbarVisuals(message.text, message.actionLabel, SnackbarDuration.Long, message.openTaskId),
                )
            } else {
                // A message with its own time (the completion toast): the time runs out here, and an
                // accessibility service that asks for longer gets it, as it does for the built-in durations.
                val timeout = accessibility?.calculateRecommendedTimeoutMillis(
                    originalTimeoutMillis = millis,
                    containsIcons = false,
                    containsText = true,
                    containsControls = message.actionLabel != null,
                ) ?: millis
                withTimeoutOrNull(timeout) {
                    snackbarHostState.showSnackbar(
                        AppSnackbarVisuals(message.text, message.actionLabel, SnackbarDuration.Indefinite, message.openTaskId),
                    )
                } ?: SnackbarResult.Dismissed
            }
            if (result == SnackbarResult.ActionPerformed) message.onAction?.invoke()
        }
    }

    // The ViewModel survives ordinary recompositions, while the saveable sheet request also
    // survives process recreation. Restart the Room lookup only when the restored request has
    // not already reached this ViewModel.
    LaunchedEffect(showTaskDetailSheet, taskDetailTaskId, authState) {
        if (
            showTaskDetailSheet &&
            taskDetailTaskId != 0L &&
            authState == AuthState.Authenticated &&
            taskDetailViewModel.uiState.value.requestedTaskId != taskDetailTaskId &&
            taskDetailViewModel.uiState.value.followedFromTaskId != taskDetailTaskId
        ) {
            taskDetailViewModel.loadTask(taskDetailTaskId)
        }
    }

    // Handle notification deep link → open TaskDetailSheet
    val initialTaskIdValue = initialTaskId?.collectAsStateWithLifecycle()?.value
    LaunchedEffect(initialTaskIdValue, authState) {
        if (initialTaskIdValue == null || initialTaskIdValue == 0L) return@LaunchedEffect
        if (authState == AuthState.Loading) return@LaunchedEffect
        if (authState != AuthState.Authenticated) {
            onInitialTaskConsumed()
            return@LaunchedEffect
        }
        taskDetailTaskId = initialTaskIdValue
        taskDetailViewModel.loadTask(initialTaskIdValue)
        showTaskDetailSheet = true
        onInitialTaskConsumed()
    }

    // Handle widget deep link → open TaskEntrySheet (optionally with project)
    val showTaskEntryValue = showTaskEntry?.collectAsStateWithLifecycle()?.value
    val showTaskEntryProjectIdValue = showTaskEntryProjectId?.collectAsStateWithLifecycle()?.value
    LaunchedEffect(showTaskEntryValue, authState) {
        if (showTaskEntryValue != true) return@LaunchedEffect
        if (authState == AuthState.Loading) return@LaunchedEffect
        if (authState != AuthState.Authenticated) {
            onShowTaskEntryConsumed()
            return@LaunchedEffect
        }
        taskEntryDefaultProjectId = showTaskEntryProjectIdValue
        showTaskEntrySheet = true
        onShowTaskEntryConsumed()
    }

    // Handle widget title click → navigate to matching screen, the same way the drawer does
    val navigateToViewValue = navigateToView?.collectAsStateWithLifecycle()?.value
    LaunchedEffect(navigateToViewValue, authState) {
        val target = navigateToViewValue ?: return@LaunchedEffect
        if (authState == AuthState.Loading) return@LaunchedEffect
        if (authState != AuthState.Authenticated) {
            onNavigateToViewConsumed()
            return@LaunchedEffect
        }
        val route = target.toRoute()
        navController.awaitGraph()
        // Already there: re-navigating would re-create a project, tag or list and reset its scroll.
        if (routeKey(route) != currentRouteKey(navController.currentBackStackEntry)) {
            navController.navigateTopLevel(route)
        }
        onNavigateToViewConsumed()
    }

    // Handle share intent → open TaskEntrySheet with shared content
    val sharedContentValue = sharedContent?.collectAsStateWithLifecycle()?.value
    // Shared text with smart dictation on: "Make tasks from this" or one task (docs/ai-assistant-v1.md, section 4).
    var shareChoice by remember { mutableStateOf<SharedContent?>(null) }
    LaunchedEffect(sharedContentValue, authState) {
        if (sharedContentValue != null && authState == AuthState.Authenticated) {
            if (aiSettings.dictationReady && !sharedContentValue.hasFiles && !sharedContentValue.text.isNullOrBlank()) {
                shareChoice = sharedContentValue
            } else {
                pendingSharedContent = sharedContentValue
                showTaskEntrySheet = true
            }
            onSharedContentConsumed()
        }
    }

    val onTaskClick: (Long) -> Unit = { taskId ->
        taskDetailTaskId = taskId
        taskDetailViewModel.loadTask(taskId)
        showTaskDetailSheet = true
    }

    var taskEntryDefaultDueDate by rememberSaveable { mutableStateOf<String?>(null) }

    val onShowTaskEntry: (Long?, String?) -> Unit = { projectId, dueDate ->
        taskEntryDefaultProjectId = projectId
        taskEntryDefaultDueDate = dueDate
        showTaskEntrySheet = true
    }

    // Tasks added through the + button's quick add count towards the "Hold + to say a task" hint.
    var taskEntryFromFab by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(authState) {
        Logger.d("VicuApp", "authState changed to $authState, currentDest=${currentDestination?.route}")
        AuthDebugLog.log("NAVIGATION", "authState=$authState currentDest=${currentDestination?.route}")
        when (authState) {
            AuthState.Unauthenticated, AuthState.NeedsReAuth -> {
                navController.awaitGraph()
                // A cold start that is not signed in already begins on Setup; navigating to it
                // again would replace its entry (and its view model) for nothing.
                if (navController.currentDestination?.hasRoute(SetupRoute::class) != true) {
                    Logger.d("VicuApp", "Navigating to SetupRoute")
                    AuthDebugLog.log("NAVIGATION", "→ SetupRoute (reason: $authState)")
                    navController.navigate(SetupRoute) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            }
            AuthState.Authenticated -> {
                navController.awaitGraph()
                val isOnSetup = navController.currentDestination?.hasRoute(SetupRoute::class) == true
                Logger.d("VicuApp", "Authenticated — isOnSetup=$isOnSetup")
                if (isOnSetup) {
                    val inboxId = authManager.getInboxProjectId()
                    Logger.d("VicuApp", "Checking inboxProjectId=$inboxId")
                    if (inboxId != null) {
                        Logger.d("VicuApp", "Setup complete — navigating to InboxRoute")
                        navController.navigate(InboxRoute) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                }
            }
            AuthState.Loading -> { /* show loading below */ }
        }
    }

    if (authState == AuthState.Loading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    // The first screen follows the auth state the app starts with: the navigation graph is built
    // once, with the state known (the spinner above waited for it), so the Inbox is neither
    // composed nor refreshed on a start that is not signed in.
    val startDestination = remember { startDestinationFor(authState) }

    // Resolve current route name for drawer/bottom bar active highlighting
    val currentRoute = currentRouteKey(navBackStackEntry)

    // A guided session or the weekly review is one thing at a time: no drawer, no bottom bar.
    val inPlanSession = currentDestination?.hasRoute(PlanMyDayRoute::class) == true
    val inGuided = currentDestination?.let { dest ->
        dest.hasRoute(GuidedSortRoute::class) || dest.hasRoute(GuidedReviewRoute::class) || dest.hasRoute(WeeklyReviewRoute::class) ||
            dest.hasRoute(PlanMyDayRoute::class)
    } ?: false

    // Disable drawer on Setup, Search screens
    val enableDrawerGestures = authState == AuthState.Authenticated && !inGuided && currentDestination?.let { dest ->
        !dest.hasRoute(SetupRoute::class) &&
            !dest.hasRoute(SearchRoute::class)
    } ?: false

    // Show bottom bar on authenticated screens (not Setup, Search or a guided session)
    val showBottomBar = authState == AuthState.Authenticated && !inGuided && currentDestination?.let { dest ->
        !dest.hasRoute(SetupRoute::class) && !dest.hasRoute(SearchRoute::class)
    } ?: false

    val drawerViewModel: DrawerViewModel = koinViewModel()
    val drawerUiState by drawerViewModel.uiState.collectAsStateWithLifecycle()

    // Collaboration (assignees and comments) for the rows, the editor and the Comments screen.
    val collabViewModel: CollabViewModel = koinViewModel()
    val collabState by collabViewModel.state.collectAsStateWithLifecycle()
    val collabSignals by collabViewModel.signals.collectAsStateWithLifecycle()
    val commentsViewModel: TaskCommentsViewModel = koinViewModel()
    val collabUi = remember(collabState, collabSignals) {
        CollabUi(
            state = collabState,
            signals = collabSignals,
            openComments = { taskId -> commentsTaskId = taskId },
            setAssigned = collabViewModel::setAssigned,
            memberPermissions = collabViewModel::memberPermissions,
        )
    }
    val projectProgress by drawerViewModel.projectProgress.collectAsStateWithLifecycle()
    val projectOpenCounts by drawerViewModel.projectOpenCounts.collectAsStateWithLifecycle()
    val fabAlignStart by drawerViewModel.fabAlignStart.collectAsStateWithLifecycle()
    // The screens' + buttons, drawn as one by AppFab, which can also be held to say a task and
    // dragged onto a day or project header.
    val fabHost = remember { AppFabHost() }
    val holdToTalk: HoldToTalk = koinInject()
    // A dictation the model could not turn into tasks comes back after a restart (docs/ai-assistant-v1.md, 4.6).
    LaunchedEffect(authState) { if (authState == AuthState.Authenticated) dictationController.restoreKept() }
    val fabHint: FabHint = koinInject()
    val fabController = remember(holdToTalk, fabHint) { FabController(holdToTalk, fabHint) }
    fabController.format = dateFormat
    fabController.onTapped = { taskEntryFromFab = true }
    fabController.onDrop = { target ->
        taskEntryFromFab = true
        onShowTaskEntry(target.projectId, target.dueDate)
    }

    // The app icon's Dictate (Android launcher shortcut, iOS quick action): smart dictation, listening,
    // when it is set up; otherwise the voice card without a hold, as "Say a task" (Add and Cancel).
    val smartDictation: DictationMic = koinInject()
    val startDictationValue = startDictation?.collectAsStateWithLifecycle()?.value
    LaunchedEffect(startDictationValue, authState) {
        if (startDictationValue != true) return@LaunchedEffect
        if (authState == AuthState.Loading) return@LaunchedEffect
        if (authState != AuthState.Authenticated) {
            onStartDictationConsumed()
            return@LaunchedEffect
        }
        // Read from the store: the collected settings start as the defaults until it has loaded.
        if (aiSettingsStore.settings.first().dictationReady) {
            smartDictation.tryIt()
        } else {
            fabController.sayTask(TaskDefaults(listName = "Inbox")) { onShowTaskEntry(null, null) }
        }
        onStartDictationConsumed()
    }
    val subtaskDisplayMode by drawerViewModel.subtaskDisplayMode.collectAsStateWithLifecycle()
    val rowDensity by drawerViewModel.rowDensity.collectAsStateWithLifecycle()

    // Ask, a sheet over the list it is opened from (next to Search in the lists' top bars, the
    // drawer, Settings' Try it).
    val askEntry: AskEntry = koinInject()
    val askViewModel: AskViewModel = koinViewModel()
    var askOpen by rememberSaveable { mutableStateOf(false) }
    val openAsk: ((TaskDefaults?) -> Unit)? = remember(aiSettings.askReady, askViewModel) {
        if (!aiSettings.askReady) {
            null
        } else {
            { defaults ->
                askEntry.list = AskList(defaults?.listName.orEmpty(), defaults?.projectId)
                askViewModel.onOpened()
                askOpen = true
                askViewModel.startTalkingIfAsked()
            }
        }
    }

    // What every surface of the app is composed under, the sheets and dialogs as much as the
    // screens: the current day and zone, the user's clock and the date phrasing, the row actions.
    val appLocals = arrayOf<ProvidedValue<*>>(
        LocalFabAlignStart provides fabAlignStart,
        LocalSubtaskDisplayMode provides subtaskDisplayMode,
        LocalRowDensity provides rowDensity,
        LocalToday provides clockDay.date,
        LocalClockDay provides clockDay,
        LocalIs24Hour provides is24Hour,
        LocalDateFormat provides dateFormat,
        LocalTaskRowActions provides taskRowActions,
        LocalCollab provides collabUi,
        LocalProjectCategories provides drawerUiState.categories,
        LocalOpenAsk provides openAsk,
    )

    // Build dynamic bottom bar items from config
    val bottomNavItems = remember(
        drawerUiState.bottomBarSlots,
        drawerUiState.bottomBarSmartListColors,
        drawerUiState.allProjects,
        drawerUiState.customLists,
        drawerUiState.inboxProjectId,
        drawerUiState.plannerEnabled,
    ) {
        resolveBottomBarItems(
            drawerUiState.bottomBarSlots,
            drawerUiState.allProjects,
            drawerUiState.customLists,
            drawerUiState.inboxProjectId,
            smartListColors = drawerUiState.bottomBarSmartListColors,
            plannerEnabled = drawerUiState.plannerEnabled,
        )
    }

    // Sync state for offline banner
    val syncStateViewModel: SyncStateViewModel = koinViewModel()
    val isOnline by syncStateViewModel.isOnline.collectAsStateWithLifecycle()
    val pendingCount by syncStateViewModel.pendingCount.collectAsStateWithLifecycle(initialValue = 0)
    val failedCount by syncStateViewModel.failedCount.collectAsStateWithLifecycle(initialValue = 0)
    // Collected only while signed in and in the foreground: collecting is what asks the server again.
    val serverUnreachable by remember(authState) {
        if (authState == AuthState.Authenticated) syncStateViewModel.serverUnreachable else flowOf(false)
    }.collectAsStateWithLifecycle(initialValue = false)

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = enableDrawerGestures,
        drawerContent = {
            CompositionLocalProvider(*appLocals) {
                DrawerContent(
                    showAsk = aiSettings.askReady,
                    state = drawerUiState,
                    currentRoute = currentRoute,
                    onNavigate = { route ->
                        scope.launch { drawerState.close() }
                        if (route is AskRoute) {
                            // Ask opens over the screen, about its list.
                            openAsk?.invoke(fabHost.specOf(navController.currentBackStackEntry?.id)?.taskDefaults)
                            return@DrawerContent
                        }
                        // Choosing the screen that is already open only closes the drawer: for a
                        // project, tag or list it used to re-create the entry (scroll and view model).
                        if (routeKey(route) != currentRoute) navController.navigateTopLevel(route)
                    },
                    onToggleProjects = drawerViewModel::toggleProjectsExpanded,
                    onToggleLists = drawerViewModel::toggleListsExpanded,
                    onToggleTags = drawerViewModel::toggleTagsExpanded,
                    onToggleProjectCollapsed = drawerViewModel::toggleProjectCollapsed,
                    projectProgress = projectProgress,
                    projectOpenCounts = projectOpenCounts,
                    progressActive = drawerState.targetValue == DrawerValue.Open,
                    onProgressRows = drawerViewModel::setProgressRows,
                    onCreateNewList = {
                        scope.launch { drawerState.close() }
                        showNewListDialog = true
                    },
                    onCreateProject = {
                        scope.launch { drawerState.close() }
                        showNewProjectDialog = true
                    },
                    onMoveProject = drawerViewModel::moveProject,
                    onToggleCategoryCollapsed = drawerViewModel::toggleCategoryCollapsed,
                    onRenameCategory = drawerViewModel::renameCategory,
                    onMoveCategory = drawerViewModel::moveCategory,
                    onDeleteCategory = drawerViewModel::deleteCategory,
                    onReorderList = drawerViewModel::reorderCustomList,
                    onReorderLabel = drawerViewModel::reorderLabel,
                )
            }
        },
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                if (showBottomBar) {
                    NavigationBar {
                        bottomNavItems.forEach { item ->
                            NavigationBarItem(
                                icon = {
                                    val iconSelected = currentRoute == item.routeName
                                    val scale by animateFloatAsState(
                                        targetValue = if (iconSelected) 1.15f else 1f,
                                        label = "navIconScale",
                                    )
                                    // contentDescription = null: the label Text already announces
                                    // the destination, so this avoids a double TalkBack announcement.
                                    Icon(
                                        item.icon,
                                        contentDescription = null,
                                        modifier = Modifier.scale(scale),
                                        // A smart list's icon is drawn in its identity colour (design-system-v1,
                                        // section 8), a project or list slot in the colour chosen for it.
                                        tint = item.identity?.color(LocalVicuColors.current.identity)
                                            ?: item.color
                                            ?: LocalContentColor.current,
                                    )
                                },
                                label = {
                                    Text(
                                        item.label,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                selected = currentRoute == item.routeName,
                                onClick = {
                                    // Ignore taps on the already-selected destination so we don't
                                    // re-navigate onto the current screen. Inbox is the start
                                    // destination and would otherwise be re-created (replaying its
                                    // loading state) on every re-tap — see issue #6.
                                    if (currentRoute != item.routeName) {
                                        navController.navigateTopLevel(item.route)
                                    }
                                },
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            Column(modifier = Modifier.padding(innerPadding)) {
                // A band at the top pays for the status bar itself. What comes below it takes off as
                // much of the status bar's inset as the bands are tall, by their measured height, so
                // the top bars stay exactly where they are until a band has outgrown the status bar.
                var offlineBandHeight by remember { mutableIntStateOf(0) }
                var failedBandHeight by remember { mutableIntStateOf(0) }
                val density = LocalDensity.current
                if (authState == AuthState.Authenticated) {
                    OfflineBanner(
                        isOffline = !isOnline,
                        serverUnreachable = serverUnreachable,
                        pendingCount = pendingCount,
                        modifier = Modifier.onSizeChanged { offlineBandHeight = it.height },
                    )
                    FailedActionsBanner(
                        failedCount = failedCount,
                        onRetry = syncStateViewModel::retryFailed,
                        onDiscard = syncStateViewModel::discardFailed,
                        modifier = Modifier
                            .consumeWindowInsets(PaddingValues(top = with(density) { offlineBandHeight.toDp() }))
                            .onSizeChanged { failedBandHeight = it.height },
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .consumeWindowInsets(
                            PaddingValues(top = with(density) { (offlineBandHeight + failedBandHeight).toDp() }),
                        ),
                ) {
                    CompositionLocalProvider(
                        *appLocals,
                        LocalAppFabHost provides fabHost,
                        LocalFabDropTargets provides fabController.dropTargets,
                    ) {
                        AppNavHost(
                            navController = navController,
                            startDestination = startDestination,
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            onNavigateToSearch = {
                                navController.navigate(SearchRoute) {
                                    launchSingleTop = true
                                }
                            },
                            onTaskClick = onTaskClick,
                            onShowTaskEntry = onShowTaskEntry,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    // The voice card, the dragged + and the hint, over the screens and under the
                    // button, so the scrim does not dim the + that is being held.
                    CompositionLocalProvider(*appLocals) {
                        FabOverlays(fabController, fabAlignStart)
                    }
                    // The + button of the screen on top, over the screens rather than in them, so a
                    // change of screen does not fade it. Placed where the screens' Scaffolds put it:
                    // 16 dp from the corner, above the system bar inset they leave free.
                    AppFab(
                        host = fabHost,
                        screen = navBackStackEntry?.id,
                        modifier = Modifier
                            .align(if (fabAlignStart) Alignment.BottomStart else Alignment.BottomEnd)
                            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom))
                            .padding(16.dp),
                        controller = fabController.takeIf { authState == AuthState.Authenticated },
                    )
                }
            }
        }
    }

    // The sheets and dialogs sit beside the screens, not inside them: same providers.
    CompositionLocalProvider(*appLocals) {
        // Task Entry Sheet
        if (showTaskEntrySheet) {
            TaskEntrySheet(
                defaultProjectId = taskEntryDefaultProjectId,
                defaultDueDate = taskEntryDefaultDueDate,
                onDismiss = {
                    showTaskEntrySheet = false
                    taskEntryDefaultDueDate = null
                    pendingSharedContent = null
                    taskEntryFromFab = false
                },
                onTaskCreated = {
                    pendingSharedContent = null
                    if (taskEntryFromFab) fabHint.onTaskAddedWithPlus()
                },
                sharedContent = pendingSharedContent,
            )
        }

        // Ask over the screens; the editor of a task it shows opens above it.
        AskSheet(
            open = askOpen && aiSettings.askReady,
            onClose = { askOpen = false },
            onTaskClick = onTaskClick,
            viewModel = askViewModel,
            // An agenda item opens its guided session over the screen Ask was opened from.
            onStartGuided = { id -> navController.openGuided(id) },
        )

        // Task Detail (full-screen edit). A task made offline keeps its editor (and the temporary id
        // it was opened with, so the editor is not rebuilt) when its create goes through.
        val taskDetailVisible = showTaskDetailSheet &&
            !taskDetailUiState.isLoading &&
            (
                taskDetailUiState.task?.id == taskDetailTaskId ||
                    (taskDetailUiState.followedFromTaskId == taskDetailTaskId && taskDetailUiState.task != null)
                )
        val editorBackState = remember { EditorBackState() }
        EditorOverlay(
            taskId = if (taskDetailVisible) taskDetailTaskId else null,
            backState = editorBackState,
            // Still open while it loads another task (a subtask): that change is instant.
            stayOpen = { showTaskDetailSheet },
        ) { editorTaskId, backEnabled ->
            TaskDetailScreen(
                taskId = editorTaskId,
                onDismiss = { showTaskDetailSheet = false },
                viewModel = taskDetailViewModel,
                onOpenTask = onTaskClick,
                backState = editorBackState,
                // While the comments are open over the editor, back closes them first.
                backEnabled = backEnabled && commentsTaskId == 0L,
            )
        }

        // The Comments screen of a task, over the editor or the list it was opened from.
        val commentsBackState = remember { EditorBackState() }
        EditorOverlay(
            taskId = commentsTaskId.takeIf { it != 0L && authState == AuthState.Authenticated },
            backState = commentsBackState,
            stayOpen = { false },
        ) { taskId, backEnabled ->
            TaskCommentsScreen(
                taskId = taskId,
                onClose = { commentsTaskId = 0L },
                viewModel = commentsViewModel,
                backState = commentsBackState,
                backEnabled = backEnabled,
            )
        }

        // Smart dictation's draft cards, over the screens and the editor.
        DictationSheet()
        shareChoice?.let { shared ->
            AlertDialog(
                onDismissRequest = { shareChoice = null },
                title = { Text("Add what you shared") },
                text = { Text("Make separate tasks from it with the assistant, or add it as one task.") },
                confirmButton = {
                    TextButton(onClick = {
                        shareChoice = null
                        val text = listOfNotNull(shared.subject, shared.text).joinToString("\n\n")
                        dictationController.start(text, TaskDefaults(listName = "Inbox"))
                    }) { Text("Make tasks") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        shareChoice = null
                        pendingSharedContent = shared
                        showTaskEntrySheet = true
                    }) { Text("One task") }
                },
            )
        }

        // App-level messages. Emitted after the editor so it draws above it.
        Box(modifier = Modifier.fillMaxSize()) {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    // Plan my day has its buttons where the bar would be: a message must not cover them.
                    .padding(bottom = if ((showBottomBar || inPlanSession) && !taskDetailVisible && commentsTaskId == 0L) 80.dp else 0.dp),
            ) { data ->
                val openTaskId = (data.visuals as? AppSnackbarVisuals)?.openTaskId
                if (openTaskId == null) {
                    VicuSnackbar(data)
                } else {
                    SnackbarWithOpen(
                        data = data,
                        onOpen = {
                            // A task made offline may have got its server id since the message came.
                            scope.launch { onTaskClick(tempIds.realIdFor(openTaskId) ?: openTaskId) }
                        },
                    )
                }
            }
        }

        NotificationPermissionSheet(notificationPermission)

        whenSheetTask?.let { target ->
            WhenSheet(
                currentDate = target.dueDate,
                onDateSelected = { dueDate -> setWhenDue(target.id, dueDate) },
                onClearDate = { setWhenDue(target.id, Constants.NULL_DATE_STRING) },
                onDismiss = { whenSheetTask = null },
            )
        }

        moveSheetTask?.let { target ->
            val inboxId by authManager.inboxProjectId.collectAsStateWithLifecycle(initialValue = null)
            ProjectPickerDialog(
                projects = drawerUiState.allProjects,
                selectedProjectId = target.projectId,
                onProjectSelected = { projectId -> moveSwiped(target, projectId) },
                onDismiss = { moveSheetTask = null },
                inboxProjectId = inboxId ?: 0L,
            )
        }

        deleteDialogTask?.let { target ->
            DeleteTaskDialog(
                descendantCount = target.descendantsDepthFirst().size,
                onDelete = { deleteSubtasks ->
                    deleteDialogTask = null
                    deleteSwiped(target.id, deleteSubtasks)
                },
                onDismiss = { deleteDialogTask = null },
            )
        }

        // New Project Dialog (the + of the drawer's Projects header)
        if (showNewProjectDialog) {
            ProjectEditDialog(
                project = null,
                projects = drawerUiState.allProjects,
                onSave = { name, hexColor, parentId ->
                    drawerViewModel.createProject(name, hexColor, parentId)
                    showNewProjectDialog = false
                },
                onDismiss = { showNewProjectDialog = false },
            )
        }

        // New Custom List Dialog (from drawer)
        if (showNewListDialog) {
            CustomListDialog(
                projects = drawerUiState.allProjects,
                labels = drawerUiState.labels,
                onSave = { list ->
                    drawerViewModel.saveCustomList(list)
                    showNewListDialog = false
                    navController.navigate(CustomListRoute(list.id))
                },
                onDismiss = { showNewListDialog = false },
                inboxProjectId = drawerUiState.inboxProjectId,
            )
        }
    }
}
