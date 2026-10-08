package com.rendyhd.vicu.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hasRoute
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
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable<SetupRoute> {
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
        composable<InboxRoute> {
            InboxScreen(
                onTaskClick = onTaskClick,
                onOpenDrawer = onOpenDrawer,
                onNavigateToSearch = onNavigateToSearch,
                onShowTaskEntry = onShowTaskEntry,
            )
        }
        composable<TodayRoute> {
            TodayScreen(
                onTaskClick = onTaskClick,
                onOpenDrawer = onOpenDrawer,
                onNavigateToSearch = onNavigateToSearch,
                onShowTaskEntry = onShowTaskEntry,
                onOpenRoutines = { navController.navigate(RoutinesRoute) { launchSingleTop = true } },
            )
        }
        composable<UpcomingRoute> {
            UpcomingScreen(
                onTaskClick = onTaskClick,
                onOpenDrawer = onOpenDrawer,
                onNavigateToSearch = onNavigateToSearch,
                onShowTaskEntry = onShowTaskEntry,
            )
        }
        composable<AnytimeRoute> {
            AnytimeScreen(
                onTaskClick = onTaskClick,
                onOpenDrawer = onOpenDrawer,
                onNavigateToSearch = onNavigateToSearch,
                onShowTaskEntry = onShowTaskEntry,
            )
        }
        composable<LogbookRoute> {
            LogbookScreen(
                onTaskClick = onTaskClick,
                onOpenDrawer = onOpenDrawer,
                onNavigateToSearch = onNavigateToSearch,
            )
        }
        composable<ReviewRoute> {
            ReviewScreen(
                onOpenDrawer = onOpenDrawer,
                onNavigateToSearch = onNavigateToSearch,
                onTaskClick = onTaskClick,
            )
        }
        composable<RoutinesRoute> {
            RoutinesScreen(
                onOpenDrawer = onOpenDrawer,
                onNavigateToSearch = onNavigateToSearch,
            )
        }
        composable<ProjectRoute> { backStackEntry ->
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
            )
        }
        composable<TagRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<TagRoute>()
            TagScreen(
                labelId = route.labelId,
                onTaskClick = onTaskClick,
                onOpenDrawer = onOpenDrawer,
                onNavigateToSearch = onNavigateToSearch,
                onShowTaskEntry = onShowTaskEntry,
            )
        }
        composable<CustomListRoute> { backStackEntry ->
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
        composable<SearchRoute> {
            SearchScreen(
                onTaskClick = onTaskClick,
                onNavigateBack = { navController.popBackStack() },
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(
                onOpenDrawer = onOpenDrawer,
            )
        }
    }
}
