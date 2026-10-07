package com.rendyhd.vicu.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class CustomList(
    val id: String,
    val name: String,
    val icon: String = "",
    val filter: CustomListFilter,
)

@Serializable
data class CustomListFilter(
    val projectIds: List<Long> = emptyList(),
    val projectFilterMode: String = "include", // "include" or "exclude"
    val addToProjectId: Long = 0L, // 0 = inbox (default)
    val sortBy: String = "due_date",
    val orderBy: String = "asc",
    val dueDateFilter: String = "all",
    val priorityFilter: List<Int> = emptyList(),
    val labelIds: List<Long> = emptyList(),
    val includeDone: Boolean = false,
    val includeTodayAllProjects: Boolean = false,
    /**
     * Whether the today / this week / this month windows also include overdue tasks. Null is the
     * synced key being absent, which means true; the editor only writes false when the user turned
     * it off (docs/cross-app-semantics-v1.md, section 3).
     */
    val includeOverdue: Boolean? = null,
) {
    /** The effective value of [includeOverdue]: absent means true. */
    val includesOverdue: Boolean get() = includeOverdue != false
}
