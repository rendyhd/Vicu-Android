package com.rendyhd.vicu.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class CustomList(
    val id: String,
    val name: String,
    val icon: String = "",
    val filter: CustomListFilter,
    /** The Vikunja saved filter this list was imported from (cross-app semantics 3.7), or null. */
    val savedFilterId: Long? = null,
)

/** One further sort key of a list (cross-app semantics 3.6). */
@Serializable
data class ListSortKey(val sortBy: String, val orderBy: String = "asc")

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
    /** A condition in Vikunja's filter language (cross-app semantics 3.5); null or blank is none. */
    val rule: String? = null,
    /** Whether the rule's terms also match a task without a value for their field. */
    val ruleIncludeNulls: Boolean = false,
    /** Sort keys after [sortBy] and [orderBy] (cross-app semantics 3.6). */
    val thenBy: List<ListSortKey> = emptyList(),
) {
    /** [rule] when it says something, else null. */
    val ruleText: String? get() = rule?.takeIf { it.isNotBlank() }

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
