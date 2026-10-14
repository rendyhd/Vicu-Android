package com.rendyhd.vicu.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Who can see a project (`GET projects/{id}/users/search`, cross-app semantics 9.1): the owner,
 * direct shares, team members and shares inherited from a parent project, in the server's order.
 * A project is shared when this holds someone besides you.
 */
@Entity(tableName = "project_members", primaryKeys = ["projectId", "userId"])
data class ProjectMemberEntity(
    val projectId: Long,
    val userId: Long,
    val username: String = "",
    val name: String = "",
    /** The server's order. */
    val position: Int = 0,
)

/**
 * The comment signals of a task in a shared project (`expand=comment_count`, `expand=is_unread`,
 * cross-app semantics 9.4). Kept apart from `tasks` so the task refresh, which never asks for
 * them, cannot reset them.
 */
@Entity(tableName = "task_signals")
data class TaskSignalEntity(
    @PrimaryKey val taskId: Long,
    val commentCount: Int = 0,
    val isUnread: Boolean = false,
)

/**
 * A comment of a task thread that was opened on this phone (cross-app semantics 9.5). A comment
 * written offline has a negative id and [pending] until the queue has sent it.
 */
@Entity(tableName = "task_comments", indices = [Index(value = ["taskId"])])
data class TaskCommentEntity(
    @PrimaryKey val id: Long,
    val taskId: Long,
    val authorId: Long = 0,
    val authorUsername: String = "",
    val authorName: String = "",
    /** HTML. */
    val comment: String = "",
    val created: String = "",
    val updated: String = "",
    val pending: Boolean = false,
)
