package com.rendyhd.vicu.di

import com.rendyhd.vicu.ui.screens.ask.AskViewModel
import com.rendyhd.vicu.ui.screens.guided.GuidedReviewViewModel
import com.rendyhd.vicu.ui.screens.guided.GuidedSortViewModel
import com.rendyhd.vicu.ui.screens.guided.WeeklyReviewViewModel
import com.rendyhd.vicu.ui.screens.settings.AssistantSettingsViewModel
import com.rendyhd.vicu.ui.SyncStateViewModel
import com.rendyhd.vicu.ui.components.collab.CollabViewModel
import com.rendyhd.vicu.ui.components.selection.SelectionViewModel
import com.rendyhd.vicu.ui.navigation.DrawerViewModel
import com.rendyhd.vicu.ui.screens.anytime.AnytimeViewModel
import com.rendyhd.vicu.ui.screens.comments.TaskCommentsViewModel
import com.rendyhd.vicu.ui.screens.customlist.CustomListViewModel
import com.rendyhd.vicu.ui.screens.inbox.InboxViewModel
import com.rendyhd.vicu.ui.screens.logbook.LogbookViewModel
import com.rendyhd.vicu.ui.screens.project.ProjectViewModel
import com.rendyhd.vicu.ui.screens.review.ReviewViewModel
import com.rendyhd.vicu.ui.screens.routines.RoutinesViewModel
import com.rendyhd.vicu.ui.screens.search.SearchViewModel
import com.rendyhd.vicu.ui.screens.settings.HiddenRoutinesViewModel
import com.rendyhd.vicu.ui.screens.settings.SettingsViewModel
import com.rendyhd.vicu.ui.screens.setup.SetupViewModel
import com.rendyhd.vicu.ui.screens.tag.TagViewModel
import com.rendyhd.vicu.ui.components.location.PlacesViewModel
import com.rendyhd.vicu.ui.screens.taskdetail.TaskDetailViewModel
import com.rendyhd.vicu.ui.screens.taskentry.TaskEntryViewModel
import com.rendyhd.vicu.ui.screens.plan.PlanMyDayViewModel
import com.rendyhd.vicu.ui.screens.plan.PlanViewModel
import com.rendyhd.vicu.ui.screens.today.TodayDayViewModel
import com.rendyhd.vicu.ui.screens.today.TodayViewModel
import com.rendyhd.vicu.ui.screens.upcoming.UpcomingViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/** Every view model of the shared UI; the same on each platform. */
val viewModelModule = module {
    viewModelOf(::SyncStateViewModel)
    viewModelOf(::SelectionViewModel)
    viewModelOf(::DrawerViewModel)
    viewModelOf(::PlanViewModel)
    viewModelOf(::PlanMyDayViewModel)
    viewModelOf(::TodayDayViewModel)
    viewModelOf(::AnytimeViewModel)
    viewModelOf(::CustomListViewModel)
    viewModelOf(::InboxViewModel)
    viewModelOf(::LogbookViewModel)
    viewModelOf(::ProjectViewModel)
    viewModelOf(::ReviewViewModel)
    viewModelOf(::RoutinesViewModel)
    viewModelOf(::HiddenRoutinesViewModel)
    viewModelOf(::SearchViewModel)
    // More constructor parameters than viewModelOf takes (22), so spelled out.
    viewModel {
        SettingsViewModel(
            authManager = get(),
            tokenStorage = get(),
            labelRepository = get(),
            projectRepository = get(),
            customListRepository = get(),
            notificationPrefsStore = get(),
            behaviorPrefsStore = get(),
            themePrefsStore = get(),
            nlpPrefsStore = get(),
            bottomBarPrefsStore = get(),
            widgetPrefsStore = get(),
            reviewPrefsStore = get(),
            logbookPrefsStore = get(),
            routinePrefsStore = get(),
            repositoryHooks = get(),
            pendingActionDao = get(),
            networkMonitor = get(),
            sessionCleanup = get(),
            apiService = get(),
            platformSettingsHooks = get(),
            syncCursor = get(),
            projectActions = get(),
            taskOrderPreferences = get(),
        )
    }
    viewModelOf(::SetupViewModel)
    viewModelOf(::TagViewModel)
    viewModelOf(::TaskDetailViewModel)
    viewModelOf(::TaskEntryViewModel)
    viewModelOf(::TodayViewModel)
    viewModelOf(::UpcomingViewModel)
    viewModelOf(::CollabViewModel)
    viewModelOf(::TaskCommentsViewModel)
    viewModelOf(::PlacesViewModel)
    viewModelOf(::AssistantSettingsViewModel)
    viewModelOf(::AskViewModel)
    viewModelOf(::GuidedSortViewModel)
    viewModelOf(::GuidedReviewViewModel)
    viewModelOf(::WeeklyReviewViewModel)
}
