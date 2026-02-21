package com.rendyhd.vicu.widget

import kotlinx.serialization.Serializable

@Serializable
enum class WidgetViewType {
    TODAY, INBOX, UPCOMING, ANYTIME, PROJECT, CUSTOM_LIST
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
)

@Serializable
data class WidgetConfig(
    val viewType: WidgetViewType = WidgetViewType.TODAY,
    val viewId: String = "",
    val viewName: String = "Today",
)
