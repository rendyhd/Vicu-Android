package com.rendyhd.vicu.ui

import com.rendyhd.vicu.util.Logger
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalAccessibilityManager
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
import com.rendyhd.vicu.data.local.ScheduleAction
import com.rendyhd.vicu.domain.model.BottomBarSlot
import com.rendyhd.vicu.domain.model.BottomBarSlotType
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.SharedContent
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.permission.NotificationPermissionCoordinator
import com.rendyhd.vicu.ui.components.picker.WhenSheet
import com.rendyhd.vicu.ui.components.settings.NotificationPermissionSheet
import com.rendyhd.vicu.ui.components.shared.CustomListDialog
import com.rendyhd.vicu.ui.components.shared.IconRegistry
import com.rendyhd.vicu.ui.components.shared.LocalFabAlignStart
import com.rendyhd.vicu.ui.components.shared.LocalClockDay
import com.rendyhd.vicu.ui.components.shared.LocalDateFormat
import com.rendyhd.vicu.ui.components.shared.LocalIs24Hour
import com.rendyhd.vicu.ui.components.shared.LocalToday
import com.rendyhd.vicu.ui.components.shared.rememberIs24HourFormat
import com.rendyhd.vicu.ui.components.shared.FailedActionsBanner
import com.rendyhd.vicu.ui.components.shared.OfflineBanner
import com.rendyhd.vicu.ui.components.shared.SmartListIdentity
import com.rendyhd.vicu.ui.theme.LocalVicuColors
import com.rendyhd.vicu.domain.repository.QuickDue
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.ui.components.task.LocalTaskRowActions
import com.rendyhd.vicu.ui.components.task.TaskEntrySheet
import com.rendyhd.vicu.ui.components.task.TaskRowActions
import com.rendyhd.vicu.ui.components.task.LocalSubtaskDisplayMode
import com.rendyhd.vicu.ui.navigation.AnytimeRoute
import com.rendyhd.vicu.ui.navigation.AppNavHost
import com.rendyhd.vicu.ui.navigation.CustomListRoute
import com.rendyhd.vicu.ui.navigation.DrawerContent
import com.rendyhd.vicu.ui.navigation.DrawerViewModel
import com.rendyhd.vicu.ui.navigation.InboxRoute
import com.rendyhd.vicu.ui.navigation.LogbookRoute
import com.rendyhd.vicu.ui.navigation.NavigationTicker
import com.rendyhd.vicu.ui.navigation.ProjectRoute
import com.rendyhd.vicu.ui.navigation.ReviewRoute
import com.rendyhd.vicu.ui.navigation.RoutinesRoute
import com.rendyhd.vicu.ui.navigation.SearchRoute
import com.rendyhd.vicu.ui.navigation.SettingsRoute
import com.rendyhd.vicu.ui.navigation.SetupRoute
import com.rendyhd.vicu.ui.navigation.TagRoute
import com.rendyhd.vicu.ui.navigation.SharedContentSaver
import com.rendyhd.vicu.ui.navigation.TodayRoute
import com.rendyhd.vicu.ui.navigation.UpcomingRoute
import com.rendyhd.vicu.ui.navigation.ViewTarget
import com.rendyhd.vicu.ui.navigation.currentRouteKey
import com.rendyhd.vicu.ui.navigation.navigateTopLevel
import com.rendyhd.vicu.ui.navigation.routeKey
import com.rendyhd.vicu.ui.navigation.startDestinationFor
import com.rendyhd.vicu.ui.navigation.toRoute
import com.rendyhd.vicu.ui.screens.taskdetail.TaskDetailScreen
import com.rendyhd.vicu.ui.screens.taskdetail.TaskDetailViewModel
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.Constants
import com.rendyhd.vicu.util.DateDisplayFormat
import com.rendyhd.vicu.util.DayClock
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal data class BottomNavItem(
    val label: String,
    val icon: ImageVector,
    val route: Any,
    val routeName: String,
    val isParameterized: Boolean = false,
    /** Set for a smart list: its icon is drawn in the identity colour. */
    val identity: SmartListIdentity? = null,
)

/** Carries the task editor's unsaved draft through process death (see [TaskDetailViewModel.currentDraftJson]). */
private class TaskDetailDraftHolder(val restored: String?)

/**
 * The bottom bar: the Inbox first, always, then the slots the user chose. A slot for something
 * that is gone (a deleted project or list) is left out, and so is a project slot for the Inbox
 * ([inboxProjectId]): the bar already starts with it.
 */
internal fun resolveBottomBarItems(
    slots: List<BottomBarSlot>,
    allProjects: List<Project>,
    customLists: List<CustomList>,
    inboxProjectId: Long,
): List<BottomNavItem> {
    val items = mutableListOf(
        BottomNavItem("Inbox", SmartListIdentity.INBOX.icon, InboxRoute, "InboxRoute", identity = SmartListIdentity.INBOX),
    )
    for (slot in slots) {
        val item = when (slot.type) {
            BottomBarSlotType.TODAY -> BottomNavItem(
                "Today", IconRegistry.resolveIcon(slot), TodayRoute, "TodayRoute",
                identity = SmartListIdentity.TODAY,
            )
            BottomBarSlotType.UPCOMING -> BottomNavItem(
                "Upcoming", IconRegistry.resolveIcon(slot), UpcomingRoute, "UpcomingRoute",
                identity = SmartListIdentity.UPCOMING,
            )
            BottomBarSlotType.ANYTIME -> BottomNavItem(
                "Anytime", IconRegistry.resolveIcon(slot), AnytimeRoute, "AnytimeRoute",
                identity = SmartListIdentity.ANYTIME,
            )
            BottomBarSlotType.PROJECT -> {
                val projectId = slot.referenceId.toLongOrNull() ?: continue
                if (projectId == inboxProjectId) continue
                val project = allProjects.find { it.id == projectId } ?: continue
                BottomNavItem(
                    project.title, IconRegistry.resolveIcon(slot),
                    ProjectRoute(projectId), "ProjectRoute/$projectId",
                    isParameterized = true,
                )
            }
            BottomBarSlotType.CUSTOM_LIST -> {
                val list = customLists.find { it.id == slot.referenceId } ?: continue
                BottomNavItem(
                    list.name, IconRegistry.resolveIcon(slot),
                    CustomListRoute(slot.referenceId), "CustomListRoute/${slot.referenceId}",
                    isParameterized = true,
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
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Sheet state
    var showTaskEntrySheet by rememberSaveable { mutableStateOf(false) }
    var taskEntryDefaultProjectId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showTaskDetailSheet by rememberSaveable { mutableStateOf(false) }
    var taskDetailTaskId by rememberSaveable { mutableLongStateOf(0L) }
    var showNewListDialog by rememberSaveable { mutableStateOf(false) }
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
    // A swipe to schedule opens the When sheet for that task (unless the swipe is set to "Urgent").
    val behaviorPrefsStore: BehaviorPrefsStore = koinInject()
    val behaviorPrefs by behaviorPrefsStore.getPrefs().collectAsStateWithLifecycle(initialValue = BehaviorPrefs())
    val swipeOpensWhen by rememberUpdatedState(behaviorPrefs.scheduleAction == ScheduleAction.DUE_TODAY)
    var whenSheetTask by remember { mutableStateOf<Task?>(null) }
    val taskRowActions = remember(taskRepository) {
        object : TaskRowActions {
            override fun scheduleDue(taskId: Long, due: QuickDue) {
                scope.launch { taskRepository.scheduleDue(taskId, due) }
            }

            override fun swipeSchedule(taskId: Long): Boolean {
                if (!swipeOpensWhen) return false
                scope.launch { whenSheetTask = taskRepository.getById(taskId).first() }
                return true
            }
        }
    }
    // Only the due date is written, on the task as it is stored now.
    fun setWhenDue(taskId: Long, dueDate: String) {
        scope.launch {
            val current = taskRepository.getById(taskId).first() ?: return@launch
            taskRepository.update(current.copy(dueDate = dueDate))
        }
    }

    // Messages for outcomes nobody is looking at (an autosave that failed after the editor
    // closed). Shown in a snackbar above everything, including the full-screen editor.
    val appMessages: AppMessages = koinInject()
    val snackbarHostState = remember { SnackbarHostState() }
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(appMessages) {
        appMessages.messages.collectLatest { message ->
            val millis = message.durationMillis
            val result = if (millis == null) {
                snackbarHostState.showSnackbar(
                    message = message.text,
                    actionLabel = message.actionLabel,
                    duration = SnackbarDuration.Long,
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
                        message = message.text,
                        actionLabel = message.actionLabel,
                        duration = SnackbarDuration.Indefinite,
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
            taskDetailViewModel.uiState.value.requestedTaskId != taskDetailTaskId
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
        // Already there: re-navigating would re-create a project, tag or list and reset its scroll.
        if (routeKey(route) != currentRouteKey(navController.currentBackStackEntry)) {
            navController.navigateTopLevel(route)
        }
        onNavigateToViewConsumed()
    }

    // Handle share intent → open TaskEntrySheet with shared content
    val sharedContentValue = sharedContent?.collectAsStateWithLifecycle()?.value
    LaunchedEffect(sharedContentValue, authState) {
        if (sharedContentValue != null && authState == AuthState.Authenticated) {
            pendingSharedContent = sharedContentValue
            showTaskEntrySheet = true
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

    LaunchedEffect(authState) {
        Logger.d("VicuApp", "authState changed to $authState, currentDest=${currentDestination?.route}")
        AuthDebugLog.log("NAVIGATION", "authState=$authState currentDest=${currentDestination?.route}")
        when (authState) {
            AuthState.Unauthenticated, AuthState.NeedsReAuth -> {
                // A cold start that is not signed in already begins on Setup; navigating to it
                // again would replace its entry (and its view model) for nothing.
                if (currentDestination?.hasRoute(SetupRoute::class) != true) {
                    Logger.d("VicuApp", "Navigating to SetupRoute")
                    AuthDebugLog.log("NAVIGATION", "→ SetupRoute (reason: $authState)")
                    navController.navigate(SetupRoute) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            }
            AuthState.Authenticated -> {
                val isOnSetup = currentDestination?.hasRoute(SetupRoute::class) == true
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

    // Disable drawer on Setup, Search screens
    val enableDrawerGestures = authState == AuthState.Authenticated && currentDestination?.let { dest ->
        !dest.hasRoute(SetupRoute::class) &&
            !dest.hasRoute(SearchRoute::class)
    } ?: false

    // Show bottom bar on authenticated screens (not Setup or Search)
    val showBottomBar = authState == AuthState.Authenticated && currentDestination?.let { dest ->
        !dest.hasRoute(SetupRoute::class) && !dest.hasRoute(SearchRoute::class)
    } ?: false

    val drawerViewModel: DrawerViewModel = koinViewModel()
    val drawerUiState by drawerViewModel.uiState.collectAsStateWithLifecycle()
    val projectProgress by drawerViewModel.projectProgress.collectAsStateWithLifecycle()
    val fabAlignStart by drawerViewModel.fabAlignStart.collectAsStateWithLifecycle()
    val subtaskDisplayMode by drawerViewModel.subtaskDisplayMode.collectAsStateWithLifecycle()

    // What every surface of the app is composed under, the sheets and dialogs as much as the
    // screens: the current day and zone, the user's clock and the date phrasing, the row actions.
    val appLocals = arrayOf<ProvidedValue<*>>(
        LocalFabAlignStart provides fabAlignStart,
        LocalSubtaskDisplayMode provides subtaskDisplayMode,
        LocalToday provides clockDay.date,
        LocalClockDay provides clockDay,
        LocalIs24Hour provides is24Hour,
        LocalDateFormat provides dateFormat,
        LocalTaskRowActions provides taskRowActions,
    )

    // Build dynamic bottom bar items from config
    val bottomNavItems = remember(
        drawerUiState.bottomBarSlots,
        drawerUiState.allProjects,
        drawerUiState.customLists,
        drawerUiState.inboxProjectId,
    ) {
        resolveBottomBarItems(
            drawerUiState.bottomBarSlots,
            drawerUiState.allProjects,
            drawerUiState.customLists,
            drawerUiState.inboxProjectId,
        )
    }

    // Sync state for offline banner
    val syncStateViewModel: SyncStateViewModel = koinViewModel()
    val isOnline by syncStateViewModel.isOnline.collectAsStateWithLifecycle()
    val pendingCount by syncStateViewModel.pendingCount.collectAsStateWithLifecycle(initialValue = 0)
    val failedCount by syncStateViewModel.failedCount.collectAsStateWithLifecycle(initialValue = 0)

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = enableDrawerGestures,
        drawerContent = {
            CompositionLocalProvider(*appLocals) {
                DrawerContent(
                    state = drawerUiState,
                    currentRoute = currentRoute,
                    onNavigate = { route ->
                        scope.launch { drawerState.close() }
                        // Choosing the screen that is already open only closes the drawer: for a
                        // project, tag or list it used to re-create the entry (scroll and view model).
                        if (routeKey(route) != currentRoute) navController.navigateTopLevel(route)
                    },
                    onToggleProjects = drawerViewModel::toggleProjectsExpanded,
                    onToggleLists = drawerViewModel::toggleListsExpanded,
                    onToggleTags = drawerViewModel::toggleTagsExpanded,
                    onToggleProjectCollapsed = drawerViewModel::toggleProjectCollapsed,
                    projectProgress = projectProgress,
                    progressActive = drawerState.targetValue == DrawerValue.Open,
                    onProgressRows = drawerViewModel::setProgressRows,
                    onCreateNewList = {
                        scope.launch { drawerState.close() }
                        showNewListDialog = true
                    },
                    onReorderProject = drawerViewModel::reorderProject,
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
                                        // A smart list's icon is drawn in its identity colour (design-system-v1, section 8).
                                        tint = item.identity?.color(LocalVicuColors.current.identity)
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
                if (authState == AuthState.Authenticated) {
                    OfflineBanner(
                        isOffline = !isOnline,
                        pendingCount = pendingCount,
                    )
                    FailedActionsBanner(
                        failedCount = failedCount,
                        onRetry = syncStateViewModel::retryFailed,
                        onDiscard = syncStateViewModel::discardFailed,
                    )
                }
                val navHostModifier = Modifier.weight(1f)
                CompositionLocalProvider(*appLocals) {
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
                        modifier = navHostModifier,
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
                },
                onTaskCreated = {
                    pendingSharedContent = null
                },
                sharedContent = pendingSharedContent,
            )
        }

        // Task Detail (full-screen edit)
        val taskDetailVisible = showTaskDetailSheet &&
            !taskDetailUiState.isLoading &&
            taskDetailUiState.task?.id == taskDetailTaskId
        if (taskDetailVisible) {
            TaskDetailScreen(
                taskId = taskDetailTaskId,
                onDismiss = { showTaskDetailSheet = false },
                viewModel = taskDetailViewModel,
                onOpenTask = onTaskClick,
            )
        }

        // App-level messages. Emitted after the editor so it draws above it.
        Box(modifier = Modifier.fillMaxSize()) {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = if (showBottomBar && !taskDetailVisible) 80.dp else 0.dp),
            )
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
