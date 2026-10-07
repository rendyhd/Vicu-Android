package com.rendyhd.vicu.ui

import com.rendyhd.vicu.util.Logger
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoveToInbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
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
import com.rendyhd.vicu.domain.model.BottomBarSlot
import com.rendyhd.vicu.domain.model.BottomBarSlotType
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.SharedContent
import com.rendyhd.vicu.ui.components.shared.CustomListDialog
import com.rendyhd.vicu.ui.components.shared.IconRegistry
import com.rendyhd.vicu.ui.components.shared.LocalFabAlignStart
import com.rendyhd.vicu.ui.components.shared.LocalClockDay
import com.rendyhd.vicu.ui.components.shared.LocalIs24Hour
import com.rendyhd.vicu.ui.components.shared.LocalToday
import com.rendyhd.vicu.ui.components.shared.rememberIs24HourFormat
import com.rendyhd.vicu.ui.components.shared.FailedActionsBanner
import com.rendyhd.vicu.ui.components.shared.OfflineBanner
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
import com.rendyhd.vicu.ui.navigation.ProjectRoute
import com.rendyhd.vicu.ui.navigation.ReviewRoute
import com.rendyhd.vicu.ui.navigation.RoutinesRoute
import com.rendyhd.vicu.ui.navigation.SearchRoute
import com.rendyhd.vicu.ui.navigation.SettingsRoute
import com.rendyhd.vicu.ui.navigation.SetupRoute
import com.rendyhd.vicu.ui.navigation.TagRoute
import com.rendyhd.vicu.ui.navigation.TodayRoute
import com.rendyhd.vicu.ui.navigation.UpcomingRoute
import com.rendyhd.vicu.ui.screens.taskdetail.TaskDetailScreen
import com.rendyhd.vicu.ui.screens.taskdetail.TaskDetailViewModel
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.DayClock
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal data class BottomNavItem(
    val label: String,
    val icon: ImageVector,
    val route: Any,
    val routeName: String,
    val isParameterized: Boolean = false,
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
        BottomNavItem("Inbox", Icons.Outlined.MoveToInbox, InboxRoute, "InboxRoute"),
    )
    for (slot in slots) {
        val item = when (slot.type) {
            BottomBarSlotType.TODAY -> BottomNavItem(
                "Today", IconRegistry.resolveIcon(slot), TodayRoute, "TodayRoute",
            )
            BottomBarSlotType.UPCOMING -> BottomNavItem(
                "Upcoming", IconRegistry.resolveIcon(slot), UpcomingRoute, "UpcomingRoute",
            )
            BottomBarSlotType.ANYTIME -> BottomNavItem(
                "Anytime", IconRegistry.resolveIcon(slot), AnytimeRoute, "AnytimeRoute",
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
    navigateToView: kotlinx.coroutines.flow.StateFlow<Pair<String, String>?>? = null,
    onNavigateToViewConsumed: () -> Unit = {},
    sharedContent: kotlinx.coroutines.flow.StateFlow<SharedContent?>? = null,
    onSharedContentConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
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
    var pendingSharedContent by remember { mutableStateOf<SharedContent?>(null) }
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

    // What a screen reader can do to a task row besides open it: the swipe gestures have no
    // equivalent for it, so the rows offer the quick due dates as actions.
    val taskRepository: TaskRepository = koinInject()
    val taskRowActions = remember(taskRepository) {
        object : TaskRowActions {
            override fun scheduleDue(taskId: Long, due: QuickDue) {
                scope.launch { taskRepository.scheduleDue(taskId, due) }
            }
        }
    }

    // Messages for outcomes nobody is looking at (an autosave that failed after the editor
    // closed). Shown in a snackbar above everything, including the full-screen editor.
    val appMessages: AppMessages = koinInject()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(appMessages) {
        appMessages.messages.collectLatest { message ->
            val result = snackbarHostState.showSnackbar(
                message = message.text,
                actionLabel = message.actionLabel,
                duration = SnackbarDuration.Long,
            )
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

    // Handle widget title click → navigate to matching screen
    val navigateToViewValue = navigateToView?.collectAsStateWithLifecycle()?.value
    LaunchedEffect(navigateToViewValue, authState) {
        val (viewType, viewId) = navigateToViewValue ?: return@LaunchedEffect
        if (authState == AuthState.Loading) return@LaunchedEffect
        if (authState != AuthState.Authenticated) {
            onNavigateToViewConsumed()
            return@LaunchedEffect
        }
        when (viewType) {
            "TODAY" -> navController.navigate(TodayRoute) { launchSingleTop = true }
            "INBOX" -> navController.navigate(InboxRoute) { launchSingleTop = true }
            "UPCOMING" -> navController.navigate(UpcomingRoute) { launchSingleTop = true }
            "ANYTIME" -> navController.navigate(AnytimeRoute) { launchSingleTop = true }
            "ROUTINES" -> navController.navigate(RoutinesRoute) { launchSingleTop = true }
            "PROJECT" -> {
                val projectId = viewId.toLongOrNull()
                if (projectId != null) {
                    navController.navigate(ProjectRoute(projectId)) { launchSingleTop = true }
                }
            }
            "CUSTOM_LIST" -> {
                if (viewId.isNotBlank()) {
                    navController.navigate(CustomListRoute(viewId)) { launchSingleTop = true }
                }
            }
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
                Logger.d("VicuApp", "Navigating to SetupRoute")
                AuthDebugLog.log("NAVIGATION", "→ SetupRoute (reason: $authState)")
                navController.navigate(SetupRoute) {
                    popUpTo(0) { inclusive = true }
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

    // Resolve current route name for drawer/bottom bar active highlighting
    val currentRoute = currentDestination?.let { dest ->
        when {
            dest.hasRoute(InboxRoute::class) -> "InboxRoute"
            dest.hasRoute(TodayRoute::class) -> "TodayRoute"
            dest.hasRoute(UpcomingRoute::class) -> "UpcomingRoute"
            dest.hasRoute(AnytimeRoute::class) -> "AnytimeRoute"
            dest.hasRoute(LogbookRoute::class) -> "LogbookRoute"
            dest.hasRoute(ReviewRoute::class) -> "ReviewRoute"
            dest.hasRoute(RoutinesRoute::class) -> "RoutinesRoute"
            dest.hasRoute(SettingsRoute::class) -> "SettingsRoute"
            dest.hasRoute(ProjectRoute::class) -> {
                val id = navBackStackEntry?.arguments?.getLong("projectId")
                "ProjectRoute/$id"
            }
            dest.hasRoute(TagRoute::class) -> {
                val id = navBackStackEntry?.arguments?.getLong("labelId")
                "TagRoute/$id"
            }
            dest.hasRoute(CustomListRoute::class) -> {
                val id = navBackStackEntry?.arguments?.getString("listId")
                "CustomListRoute/$id"
            }
            else -> null
        }
    }

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
    val fabAlignStart by drawerViewModel.fabAlignStart.collectAsStateWithLifecycle()
    val subtaskDisplayMode by drawerViewModel.subtaskDisplayMode.collectAsStateWithLifecycle()

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
            DrawerContent(
                state = drawerUiState,
                currentRoute = currentRoute,
                onNavigate = { route ->
                    scope.launch { drawerState.close() }
                    val isParameterized = route is ProjectRoute ||
                        route is TagRoute ||
                        route is CustomListRoute
                    navController.navigate(route) {
                        popUpTo(navController.graph.startDestinationId) {
                            saveState = !isParameterized
                        }
                        launchSingleTop = !isParameterized
                        restoreState = !isParameterized
                    }
                },
                onToggleProjects = drawerViewModel::toggleProjectsExpanded,
                onToggleLists = drawerViewModel::toggleListsExpanded,
                onToggleTags = drawerViewModel::toggleTagsExpanded,
                onToggleProjectCollapsed = drawerViewModel::toggleProjectCollapsed,
                onCreateNewList = {
                    scope.launch { drawerState.close() }
                    showNewListDialog = true
                },
                onReorderProject = drawerViewModel::reorderProject,
                onReorderList = drawerViewModel::reorderCustomList,
                onReorderLabel = drawerViewModel::reorderLabel,
            )
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
                                        val isStartDest = item.route is InboxRoute
                                        navController.navigate(item.route) {
                                            popUpTo(navController.graph.startDestinationId) {
                                                saveState = !item.isParameterized && !isStartDest
                                            }
                                            launchSingleTop = !item.isParameterized
                                            restoreState = !item.isParameterized && !isStartDest
                                        }
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
                CompositionLocalProvider(
                    LocalFabAlignStart provides fabAlignStart,
                    LocalSubtaskDisplayMode provides subtaskDisplayMode,
                    LocalToday provides clockDay.date,
                    LocalClockDay provides clockDay,
                    LocalIs24Hour provides is24Hour,
                    LocalTaskRowActions provides taskRowActions,
                ) {
                    AppNavHost(
                        navController = navController,
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
