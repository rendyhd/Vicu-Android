package com.rendyhd.vicu.widget

import com.rendyhd.vicu.ui.navigation.ViewTarget
import kotlinx.serialization.Serializable

@Serializable
enum class WidgetViewType {
    TODAY, INBOX, UPCOMING, ANYTIME, PROJECT, CUSTOM_LIST
}

/**
 * The screen a tap on the widget title opens. Null when the widget names a project that has no
 * usable id; the title then just opens the app.
 */
fun WidgetViewType.toViewTarget(viewId: String): ViewTarget? = when (this) {
    WidgetViewType.TODAY -> ViewTarget.Today
    WidgetViewType.INBOX -> ViewTarget.Inbox
    WidgetViewType.UPCOMING -> ViewTarget.Upcoming
    WidgetViewType.ANYTIME -> ViewTarget.Anytime
    WidgetViewType.PROJECT -> viewId.toLongOrNull()?.let { ViewTarget.Project(it) }
    WidgetViewType.CUSTOM_LIST -> viewId.takeIf { it.isNotBlank() }?.let { ViewTarget.CustomList(it) }
}

@Serializable
data class WidgetTaskItem(
    val id: Long,
    val title: String,
    val projectName: String = "",
    val dueDate: String = "",
    val priority: Int = 0,
    val done: Boolean = false,
)

@Serializable
data class TaskWidgetState(
    val viewType: WidgetViewType = WidgetViewType.TODAY,
    val viewId: String = "",
    val viewName: String = "Today",
    val tasks: List<WidgetTaskItem> = emptyList(),
    val totalCount: Int = 0,
    val lastUpdated: String = "",
    val error: String? = null,
    val smartAdd: Boolean = true,
    val contextNav: Boolean = true,
    val addToProjectId: Long = 0L, // for custom lists: which project the + button targets
)

@Serializable
data class WidgetConfig(
    val viewType: WidgetViewType = WidgetViewType.TODAY,
    val viewId: String = "",
    val viewName: String = "Today",
)
