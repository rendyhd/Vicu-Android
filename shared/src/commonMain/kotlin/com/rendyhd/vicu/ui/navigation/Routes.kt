package com.rendyhd.vicu.ui.navigation

import kotlinx.serialization.Serializable

@Serializable object SetupRoute
@Serializable object InboxRoute
@Serializable object TodayRoute

/** The day planner (docs/cross-app-semantics-v1.md, section 16); only while the planner is on. */
@Serializable object PlanRoute

/** A Plan my day session (contract 14.10), opened from the Plan screen. */
@Serializable object PlanMyDayRoute
@Serializable object UpcomingRoute
@Serializable object AnytimeRoute
@Serializable object LogbookRoute
@Serializable object ReviewRoute
@Serializable object RoutinesRoute
@Serializable data class ProjectRoute(val projectId: Long)
@Serializable data class TagRoute(val labelId: Long)
@Serializable data class CustomListRoute(val listId: String)
@Serializable object SearchRoute
@Serializable object SettingsRoute
@Serializable object AskRoute

/** A guided session over one queue (docs/cross-app-semantics-v1.md, section 14.2): `inbox`, `overdue` or `waiting`. */
@Serializable data class GuidedSortRoute(val kind: String)

/** The project review session (section 14.4). */
@Serializable object GuidedReviewRoute

/** The weekly review (section 14.7). */
@Serializable object WeeklyReviewRoute

/**
 * Returns a project route only when it would open a different project.
 *
 * Project destinations are drill-down screens: parent and child projects must have distinct
 * back-stack entries so each receives its own SavedStateHandle and ViewModel.
 */
internal fun projectRouteToPush(
    currentProjectId: Long?,
    targetProjectId: Long,
): ProjectRoute? =
    if (currentProjectId == targetProjectId) null else ProjectRoute(targetProjectId)
