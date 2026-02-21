package com.rendyhd.vicu.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.rendyhd.vicu.ui.screens.anytime.AnytimeScreen
import com.rendyhd.vicu.ui.screens.customlist.CustomListScreen
import com.rendyhd.vicu.ui.screens.inbox.InboxScreen
import com.rendyhd.vicu.ui.screens.logbook.LogbookScreen
import com.rendyhd.vicu.ui.screens.project.ProjectScreen
import com.rendyhd.vicu.ui.screens.search.SearchScreen
import com.rendyhd.vicu.ui.screens.settings.SettingsScreen
import com.rendyhd.vicu.ui.screens.setup.SetupScreen
import com.rendyhd.vicu.ui.screens.tag.TagScreen
import com.rendyhd.vicu.ui.screens.today.TodayScreen
import com.rendyhd.vicu.ui.screens.upcoming.UpcomingScreen

@Composable
fun AppNavHost(
    navController: NavHostController,
    onOpenDrawer: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onTaskClick: (Long) -> Unit = {},
    onShowTaskEntry: (Long?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = InboxRoute,
        modifier = modifier,
    ) {
        composable<SetupRoute> {
            SetupScreen(
                onSetupComplete = {
                    navController.navigate(InboxRoute) {
                        popUpTo(0) { inclusive = true }
                    }
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
        composable<ProjectRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<ProjectRoute>()
            ProjectScreen(
                projectId = route.projectId,
                onTaskClick = onTaskClick,
                onOpenDrawer = onOpenDrawer,
                onNavigateToSearch = onNavigateToSearch,
                onShowTaskEntry = onShowTaskEntry,
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
                onNavigateToSearch = onNavigateToSearch,
            )
        }
    }
}
