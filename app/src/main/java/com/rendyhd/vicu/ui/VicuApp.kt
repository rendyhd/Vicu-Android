package com.rendyhd.vicu.ui

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AllInclusive
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.MoveToInbox
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.auth.AuthState
import com.rendyhd.vicu.ui.components.shared.OfflineBanner
import com.rendyhd.vicu.ui.components.task.TaskEntrySheet
import com.rendyhd.vicu.ui.navigation.AnytimeRoute
import com.rendyhd.vicu.ui.navigation.AppNavHost
import com.rendyhd.vicu.ui.navigation.CustomListRoute
import com.rendyhd.vicu.ui.navigation.DrawerContent
import com.rendyhd.vicu.ui.navigation.DrawerViewModel
import com.rendyhd.vicu.ui.navigation.InboxRoute
import com.rendyhd.vicu.ui.navigation.LogbookRoute
import com.rendyhd.vicu.ui.navigation.ProjectRoute
import com.rendyhd.vicu.ui.navigation.SearchRoute
import com.rendyhd.vicu.ui.navigation.SettingsRoute
import com.rendyhd.vicu.ui.navigation.SetupRoute
import com.rendyhd.vicu.ui.navigation.TagRoute
import com.rendyhd.vicu.ui.navigation.TodayRoute
import com.rendyhd.vicu.ui.navigation.UpcomingRoute
import com.rendyhd.vicu.ui.components.shared.CustomListDialog
import com.rendyhd.vicu.ui.screens.taskdetail.TaskDetailSheet
import com.rendyhd.vicu.domain.model.SharedContent
import kotlinx.coroutines.launch

private data class BottomNavItem(
    val label: String,
    val icon: ImageVector,
    val route: Any,
    val routeName: String,
)

private val BOTTOM_NAV_ITEMS = listOf(
    BottomNavItem("Inbox", Icons.Outlined.MoveToInbox, InboxRoute, "InboxRoute"),
    BottomNavItem("Today", Icons.Outlined.WbSunny, TodayRoute, "TodayRoute"),
    BottomNavItem("Upcoming", Icons.Outlined.CalendarMonth, UpcomingRoute, "UpcomingRoute"),
    BottomNavItem("Anytime", Icons.Outlined.AllInclusive, AnytimeRoute, "AnytimeRoute"),
)

@Composable
fun VicuApp(
    authManager: AuthManager,
    initialTaskId: kotlinx.coroutines.flow.StateFlow<Long?>? = null,
    onInitialTaskConsumed: () -> Unit = {},
    showTaskEntry: kotlinx.coroutines.flow.StateFlow<Boolean>? = null,
    onShowTaskEntryConsumed: () -> Unit = {},
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

    // Handle notification deep link → open TaskDetailSheet
    val initialTaskIdValue = initialTaskId?.collectAsStateWithLifecycle()?.value
    LaunchedEffect(initialTaskIdValue) {
        if (initialTaskIdValue != null && initialTaskIdValue != 0L) {
            taskDetailTaskId = initialTaskIdValue
            showTaskDetailSheet = true
            onInitialTaskConsumed()
        }
    }

    // Handle widget deep link → open TaskEntrySheet
    val showTaskEntryValue = showTaskEntry?.collectAsStateWithLifecycle()?.value
    LaunchedEffect(showTaskEntryValue) {
        if (showTaskEntryValue == true) {
            showTaskEntrySheet = true
            onShowTaskEntryConsumed()
        }
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
        showTaskDetailSheet = true
    }

    var taskEntryDefaultDueDate by rememberSaveable { mutableStateOf<String?>(null) }

    val onShowTaskEntry: (Long?, String?) -> Unit = { projectId, dueDate ->
        taskEntryDefaultProjectId = projectId
        taskEntryDefaultDueDate = dueDate
        showTaskEntrySheet = true
    }

    LaunchedEffect(authState) {
        Log.d("VicuApp", "authState changed to $authState, currentDest=${currentDestination?.route}")
        when (authState) {
            AuthState.Unauthenticated, AuthState.NeedsReAuth -> {
                Log.d("VicuApp", "Navigating to SetupRoute")
                navController.navigate(SetupRoute) {
                    popUpTo(0) { inclusive = true }
                }
            }
            AuthState.Authenticated -> {
                val isOnSetup = currentDestination?.hasRoute(SetupRoute::class) == true
                Log.d("VicuApp", "Authenticated — isOnSetup=$isOnSetup")
                if (isOnSetup) {
                    val inboxId = authManager.getInboxProjectId()
                    Log.d("VicuApp", "Checking inboxProjectId=$inboxId")
                    if (inboxId != null) {
                        Log.d("VicuApp", "Setup complete — navigating to InboxRoute")
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

    val drawerViewModel: DrawerViewModel = hiltViewModel()
    val drawerUiState by drawerViewModel.uiState.collectAsStateWithLifecycle()

    // Sync state for offline banner
    val syncStateViewModel: SyncStateViewModel = hiltViewModel()
    val isOnline by syncStateViewModel.isOnline.collectAsStateWithLifecycle()
    val pendingCount by syncStateViewModel.pendingCount.collectAsStateWithLifecycle(initialValue = 0)

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
                onCreateNewList = {
                    scope.launch { drawerState.close() }
                    showNewListDialog = true
                },
            )
        },
    ) {
        Scaffold(
            bottomBar = {
                if (showBottomBar) {
                    NavigationBar {
                        BOTTOM_NAV_ITEMS.forEach { item ->
                            NavigationBarItem(
                                icon = { Icon(item.icon, contentDescription = item.label) },
                                label = { Text(item.label) },
                                selected = currentRoute == item.routeName,
                                onClick = {
                                    navController.navigate(item.route) {
                                        popUpTo(navController.graph.startDestinationId) {
                                            inclusive = false
                                        }
                                        launchSingleTop = true
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
                }
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
                    modifier = Modifier.weight(1f),
                )
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

    // Task Detail Sheet
    if (showTaskDetailSheet) {
        TaskDetailSheet(
            taskId = taskDetailTaskId,
            onDismiss = { showTaskDetailSheet = false },
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
        )
    }
}
