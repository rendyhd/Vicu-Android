package com.rendyhd.vicu.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.rendyhd.vicu.ui.screens.anytime.AnytimeScreen
import com.rendyhd.vicu.ui.screens.customlist.CustomListScreen
import com.rendyhd.vicu.ui.screens.inbox.InboxScreen
import com.rendyhd.vicu.ui.screens.logbook.LogbookScreen
import com.rendyhd.vicu.ui.screens.project.ProjectScreen
import com.rendyhd.vicu.ui.screens.review.ReviewScreen
import com.rendyhd.vicu.ui.screens.routines.RoutinesScreen
import com.rendyhd.vicu.ui.screens.search.SearchScreen
import com.rendyhd.vicu.ui.screens.settings.SettingsScreen
import com.rendyhd.vicu.ui.screens.setup.SetupScreen
import com.rendyhd.vicu.ui.screens.tag.TagScreen
import com.rendyhd.vicu.ui.screens.today.TodayScreen
import com.rendyhd.vicu.ui.screens.upcoming.UpcomingScreen
import com.rendyhd.vicu.permission.NotificationPermissionCoordinator
import org.koin.compose.koinInject

@Composable
fun AppNavHost(
    navController: NavHostController,
    startDestination: Any = InboxRoute,
    onOpenDrawer: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onTaskClick: (Long) -> Unit = {},
    onShowTaskEntry: (Long?, String?) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    // Setup finishes here (SetupScreen), not in VicuApp's auth effect: the session turns
    // Authenticated at the token step, before the Inbox project is chosen.
    val notificationPermission: NotificationPermissionCoordinator = koinInject()
    val axisPx = with(LocalDensity.current) { SHARED_AXIS_DP.dp.roundToPx() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val reveal = remember { ScreenReveal() }
    val transitions = remember(axisPx, rtl, navController) {
        NavTransitions(
            axisPx,
            rtl,
            isOnBackStack = { entry -> navController.currentBackStack.value.any { it.id == entry.id } },
            reveal = reveal,
        )
    }
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = { transitions.enter(this) },
        exitTransition = { transitions.exit(this) },
        popEnterTransition = { transitions.popEnter(this) },
        popExitTransition = { transitions.popExit(this) },
    ) {
        screen<SetupRoute>(reveal) {
            SetupScreen(
                onSetupComplete = {
                    navController.navigate(InboxRoute) {
                        popUpTo(0) { inclusive = true }
                    }
                    // The one place the app first asks for notifications: setup is done.
                    notificationPermission.onSetupCompleted()
                },
            )
        }
        screen<InboxRoute>(reveal) {
            InboxScreen(
                onTaskClick = onTaskClick,
                onOpenDrawer = onOpenDrawer,
                onNavigateToSearch = onNavigateToSearch,
                onShowTaskEntry = onShowTaskEntry,
            )
        }
        screen<TodayRoute>(reveal) {
            TodayScreen(
                onTaskClick = onTaskClick,
                onOpenDrawer = onOpenDrawer,
                onNavigateToSearch = onNavigateToSearch,
                onShowTaskEntry = onShowTaskEntry,
                onOpenRoutines = { navController.navigate(RoutinesRoute) { launchSingleTop = true } },
                onOpenUpcoming = { navController.navigateTopLevel(UpcomingRoute) },
            )
        }
        screen<UpcomingRoute>(reveal) {
            UpcomingScreen(
                onTaskClick = onTaskClick,
                onOpenDrawer = onOpenDrawer,
                onNavigateToSearch = onNavigateToSearch,
                onShowTaskEntry = onShowTaskEntry,
            )
        }
        screen<AnytimeRoute>(reveal) {
            AnytimeScreen(
                onTaskClick = onTaskClick,
                onOpenDrawer = onOpenDrawer,
                onNavigateToSearch = onNavigateToSearch,
                onShowTaskEntry = onShowTaskEntry,
            )
        }
        screen<LogbookRoute>(reveal) {
            LogbookScreen(
                onTaskClick = onTaskClick,
                onOpenDrawer = onOpenDrawer,
                onNavigateToSearch = onNavigateToSearch,
            )
        }
        screen<ReviewRoute>(reveal) {
            ReviewScreen(
                onOpenDrawer = onOpenDrawer,
                onNavigateToSearch = onNavigateToSearch,
                onTaskClick = onTaskClick,
            )
        }
        screen<RoutinesRoute>(reveal) {
            RoutinesScreen(
                onOpenDrawer = onOpenDrawer,
                onNavigateToSearch = onNavigateToSearch,
            )
        }
        screen<ProjectRoute>(reveal) { backStackEntry ->
            val route = backStackEntry.toRoute<ProjectRoute>()
            ProjectScreen(
                projectId = route.projectId,
                onTaskClick = onTaskClick,
                onOpenDrawer = onOpenDrawer,
                onNavigateToSearch = onNavigateToSearch,
                onShowTaskEntry = onShowTaskEntry,
                onProjectClick = { childProjectId ->
                    val currentProjectId = navController.currentBackStackEntry
                        ?.takeIf { it.destination.hasRoute(ProjectRoute::class) }
                        ?.toRoute<ProjectRoute>()
                        ?.projectId

                    // Do not use launchSingleTop here. Parent and child projects share the same
                    // destination type, so singleTop would reuse the parent's entry and ViewModel.
                    projectRouteToPush(currentProjectId, childProjectId)?.let { routeToPush ->
                        navController.navigate(routeToPush)
                    }
                },
                // Archived or deleted from its own menu: back, like a deleted custom list.
                onProjectGone = {
                    if (!navController.popBackStack()) {
                        navController.navigate(InboxRoute) { launchSingleTop = true }
                    }
                },
            )
        }
        screen<TagRoute>(reveal) { backStackEntry ->
            val route = backStackEntry.toRoute<TagRoute>()
            TagScreen(
                labelId = route.labelId,
                onTaskClick = onTaskClick,
                onOpenDrawer = onOpenDrawer,
                onNavigateToSearch = onNavigateToSearch,
                onShowTaskEntry = onShowTaskEntry,
            )
        }
        screen<CustomListRoute>(reveal) { backStackEntry ->
            val route = backStackEntry.toRoute<CustomListRoute>()
            CustomListScreen(
                listId = route.listId,
                onTaskClick = onTaskClick,
                onOpenDrawer = onOpenDrawer,
                onNavigateToSearch = onNavigateToSearch,
                onShowTaskEntry = onShowTaskEntry,
                onListDeleted = {
                    if (!navController.popBackStack()) {
                        navController.navigate(InboxRoute) { launchSingleTop = true }
                    }
                },
            )
        }
        screen<SearchRoute>(reveal) {
            SearchScreen(
                onTaskClick = onTaskClick,
                onNavigateBack = { navController.popBackStack() },
            )
        }
        screen<SettingsRoute>(reveal) {
            SettingsScreen(
                onOpenDrawer = onOpenDrawer,
            )
        }
    }
}

/** A destination whose content [ScreenReveal] shows (see [RevealedScreen]). */
private inline fun <reified T : Any> NavGraphBuilder.screen(
    reveal: ScreenReveal,
    noinline content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable<T> { entry -> RevealedScreen(entry, reveal) { content(entry) } }
}
