package com.rendyhd.vicu.ui.components.collab

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import com.rendyhd.vicu.domain.model.CollabState
import com.rendyhd.vicu.domain.model.ProjectAccess
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskSignal
import com.rendyhd.vicu.domain.model.User
import com.rendyhd.vicu.domain.model.toCollabUser
import com.rendyhd.vicu.util.Collaboration

/**
 * Collaboration as the rows, the editor and the comments screen see it (docs/cross-app-semantics-v1.md
 * section 9): who you are, the access of every project, the comment signals of the tasks in shared
 * projects, and the two things a row or a chip can start. Provided once at the app root; without a
 * provider every project looks private and nothing new is drawn.
 */
@Immutable
data class CollabUi(
    val state: CollabState = CollabState(),
    val signals: Map<Long, TaskSignal> = emptyMap(),
    /** Opens the Comments screen of a task. */
    val openComments: (taskId: Long) -> Unit = {},
    /** Assigns [person] to [task] or takes them off it; optimistic, with a message when refused. */
    val setAssigned: (task: Task, person: User, assigned: Boolean) -> Unit = { _, _, _ -> },
    /** The permission of each member of a project, by user id, for "Can view only"; read from the server. */
    val memberPermissions: suspend (projectId: Long) -> Map<Long, Int?> = { emptyMap() },
) {
    fun access(projectId: Long): ProjectAccess = state.access(projectId)

    /** The signal of [taskId] when its project is shared, else none. */
    fun signal(task: Task): TaskSignal? =
        if (access(task.projectId).shared) signals[task.id] ?: TaskSignal() else null

    /** The assignees of [task] in contract order (you first) when its project is shared, else none. */
    fun assignees(task: Task): List<User> =
        if (access(task.projectId).shared && task.assignees.isNotEmpty()) {
            Collaboration.orderPeople(task.assignees, state.me) { it.id }
        } else {
            emptyList()
        }

    /** "You, Maya" and the like, for [people] already in order. */
    fun nameList(people: List<User>): String = Collaboration.nameList(people.map { it.toCollabUser() }, state.me)
}

val LocalCollab = compositionLocalOf { CollabUi() }
