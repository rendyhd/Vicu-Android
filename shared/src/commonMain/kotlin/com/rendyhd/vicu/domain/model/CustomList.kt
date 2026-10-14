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
    /**
     * "me", "others" or "nobody" (cross-app semantics 3.3); null is no condition. Another string,
     * from a newer app, is kept and is no condition either.
     */
    val assigned: String? = null,
    /** "unread" or "any"; null (or another string) is no condition. */
    val comments: String? = null,
    /** Only tasks of shared projects. */
    val sharedOnly: Boolean = false,
    /** Only tasks changed on the last N local days, today included; null or below 1 is no condition. */
    val changedWithinDays: Int? = null,
) {
    /** The effective value of [includeOverdue]: absent means true. */
    val includesOverdue: Boolean get() = includeOverdue != false

    /** [changedWithinDays] when it is a condition (1 or more), else null. */
    val changedDays: Int? get() = changedWithinDays?.takeIf { it >= 1 }

    /** Whether a condition reads who you are or which projects are shared. */
    val hasCollaborationCondition: Boolean
        get() = assigned in ASSIGNED_VALUES || comments in COMMENTS_VALUES || sharedOnly

    /** Whether the list needs comment counts or unread marks. */
    val needsCommentSignals: Boolean get() = comments in COMMENTS_VALUES

    companion object {
        /** The [assigned] values this version knows, in the order the editor offers them. */
        val ASSIGNED_VALUES: List<String> = listOf("me", "others", "nobody")

        /** The [comments] values this version knows, in the order the editor offers them. */
        val COMMENTS_VALUES: List<String> = listOf("unread", "any")
    }
}
