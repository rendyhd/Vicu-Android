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
import com.rendyhd.vicu.ui.components.shared.LocalOpenAsk
import com.rendyhd.vicu.ui.screens.anytime.AnytimeScreen
import com.rendyhd.vicu.ui.screens.pro.FlaggedScreen
import com.rendyhd.vicu.ui.screens.pro.PeopleScreen
import com.rendyhd.vicu.ui.screens.pro.PersonScreen
import com.rendyhd.vicu.ui.screens.pro.WaitingOnScreen
import com.rendyhd.vicu.ui.screens.customlist.CustomListScreen
import com.rendyhd.vicu.ui.screens.guided.GuidedReviewScreen
import com.rendyhd.vicu.ui.screens.guided.GuidedSortScreen
import com.rendyhd.vicu.ui.screens.guided.WeeklyReviewScreen
import com.rendyhd.vicu.ui.screens.plan.PlanMyDayScreen
import com.rendyhd.vicu.util.Guided
import com.rendyhd.vicu.util.GuidedQueue
import com.rendyhd.vicu.ui.screens.inbox.InboxScreen
import com.rendyhd.vicu.ui.screens.logbook.LogbookScreen
import com.rendyhd.vicu.ui.screens.project.ProjectScreen
import com.rendyhd.vicu.ui.screens.review.ReviewScreen
import com.rendyhd.vicu.ui.screens.routines.RoutinesScreen
import com.rendyhd.vicu.ui.screens.search.SearchScreen
import com.rendyhd.vicu.ui.screens.settings.SettingsScreen
import com.rendyhd.vicu.ui.screens.ask.AskEntry
import com.rendyhd.vicu.ui.screens.ask.AskScreen
import com.rendyhd.vicu.ui.screens.setup.SetupScreen
import com.rendyhd.vicu.ui.screens.tag.TagScreen
import com.rendyhd.vicu.ui.screens.plan.PlanScreen
import com.rendyhd.vicu.ui.screens.today.TodayScreen
import com.rendyhd.vicu.ui.screens.upcoming.UpcomingScreen
import com.rendyhd.vicu.permission.NotificationPermissionCoordinator
import com.rendyhd.vicu.voice.TaskDefaults
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
                onSortInbox = { navController.openGuided(Guided.STEP_INBOX) },
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
                onOpenPlan = { navController.navigateTopLevel(PlanRoute) },
            )
        }
        screen<PlanRoute>(reveal) {
            PlanScreen(
                onTaskClick = onTaskClick,
                onOpenDrawer = onOpenDrawer,
                onPlannerOff = { navController.navigateTopLevel(TodayRoute) },
                onPlanMyDay = { navController.navigate(PlanMyDayRoute) { launchSingleTop = true } },
            )
        }
        screen<PlanMyDayRoute>(reveal) {
            PlanMyDayScreen(
                onClose = { navController.popBackStack() },
                onTaskClick = onTaskClick,
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
                onStartReview = { navController.openGuided(Guided.STEP_REVIEW) },
                onWeeklyReview = { navController.openGuided(Guided.AGENDA_WEEKLY) },
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
        // Pro (contract 21): a link or the history may open one with Pro off; it says so.
        val openProSettings = { navController.navigateTopLevel(SettingsRoute) }
        val openPerson = { labelId: Long -> navController.navigate(PersonRoute(labelId)) }
        screen<FlaggedRoute>(reveal) {
            FlaggedScreen(
                onTaskClick = onTaskClick,
                onOpenDrawer = onOpenDrawer,
                onNavigateToSearch = onNavigateToSearch,
                onOpenSettings = openProSettings,
            )
        }
        screen<WaitingOnRoute>(reveal) {
            WaitingOnScreen(
                onTaskClick = onTaskClick,
                onOpenDrawer = onOpenDrawer,
                onNavigateToSearch = onNavigateToSearch,
                onOpenSettings = openProSettings,
                onOpenPerson = openPerson,
            )
        }
        screen<PeopleRoute>(reveal) {
            PeopleScreen(
                onOpenDrawer = onOpenDrawer,
                onNavigateToSearch = onNavigateToSearch,
                onOpenSettings = openProSettings,
                onOpenPerson = openPerson,
            )
        }
        screen<PersonRoute>(reveal) {
            PersonScreen(
                onTaskClick = onTaskClick,
                onOpenDrawer = onOpenDrawer,
                onNavigateToSearch = onNavigateToSearch,
                onOpenSettings = openProSettings,
            )
        }
        screen<SearchRoute>(reveal) {
            SearchScreen(
                onTaskClick = onTaskClick,
                onNavigateBack = { navController.popBackStack() },
            )
        }
        screen<SettingsRoute>(reveal) {
            val askEntry: AskEntry = koinInject()
            val openAsk = LocalOpenAsk.current
            SettingsScreen(
                onOpenDrawer = onOpenDrawer,
                onOpenAsk = { talk ->
                    askEntry.startTalking = talk
                    openAsk?.invoke(TaskDefaults(listName = "Inbox"))
                },
            )
        }
        screen<AskRoute>(reveal) {
            AskScreen(
                onOpenDrawer = onOpenDrawer,
                onTaskClick = onTaskClick,
                onOpenSettings = { navController.navigateTopLevel(SettingsRoute) },
                onStartGuided = { id -> navController.openGuided(id) },
            )
        }
        screen<GuidedSortRoute>(reveal) {
            GuidedSortScreen(
                onClose = { navController.popBackStack() },
                onTaskClick = onTaskClick,
            )
        }
        screen<GuidedReviewRoute>(reveal) {
            GuidedReviewScreen(
                onClose = { navController.popBackStack() },
                onTaskClick = onTaskClick,
            )
        }
        screen<WeeklyReviewRoute>(reveal) {
            WeeklyReviewScreen(
                onClose = { navController.popBackStack() },
                onOpenSort = { queue -> navController.openGuided(queue.id) },
                onOpenReview = { navController.openGuided(Guided.STEP_REVIEW) },
                onQuickAdd = { onShowTaskEntry(null, null) },
                onTaskClick = onTaskClick,
            )
        }
    }
}

/**
 * Opens a guided session or the weekly review (docs/cross-app-semantics-v1.md, section 14) by the
 * id Ask's agenda and the weekly review's steps use: `inbox`, `overdue`, `waiting`, `review` or
 * `weekly`. It goes on top of the screen it was opened from, so closing it goes back there.
 */
fun NavHostController.openGuided(id: String) {
    val route: Any = when (id) {
        Guided.STEP_REVIEW -> GuidedReviewRoute
        Guided.AGENDA_WEEKLY -> WeeklyReviewRoute
        else -> GuidedSortRoute(GuidedQueue.of(id)?.id ?: return)
    }
    navigate(route) { launchSingleTop = true }
}

/** A destination whose content [ScreenReveal] shows (see [RevealedScreen]). */
private inline fun <reified T : Any> NavGraphBuilder.screen(
    reveal: ScreenReveal,
    noinline content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable<T> { entry ->
        // Before the screen, so the screen's own Back handlers come ahead of it.
        EdgeDrawerBack(entry)
        RevealedScreen(entry, reveal) { content(entry) }
    }
}
