package com.rendyhd.vicu.ui.navigation

import kotlinx.serialization.Serializable

@Serializable object SetupRoute
@Serializable object InboxRoute
@Serializable object TodayRoute
@Serializable object UpcomingRoute
@Serializable object AnytimeRoute
@Serializable object LogbookRoute
@Serializable object ReviewRoute
@Serializable data class ProjectRoute(val projectId: Long)
@Serializable data class TagRoute(val labelId: Long)
@Serializable data class CustomListRoute(val listId: String)
@Serializable object SearchRoute
@Serializable object SettingsRoute
