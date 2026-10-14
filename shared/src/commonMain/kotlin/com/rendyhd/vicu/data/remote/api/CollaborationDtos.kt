package com.rendyhd.vicu.data.remote.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A comment on a task (`GET tasks/{id}/comments`). `comment` is HTML (cross-app semantics 9.5). */
@Serializable
data class TaskCommentDto(
    val id: Long = 0,
    val comment: String = "",
    val author: UserDto? = null,
    val created: String = "",
    val updated: String = "",
)

@Serializable
data class CommentBodyDto(val comment: String)

/** A direct share of a project (`GET projects/{id}/users`): the user and what they may do. */
@Serializable
data class ProjectUserShareDto(
    val id: Long = 0,
    val username: String = "",
    val name: String = "",
    val permission: Int = 0,
)

/** A team share of a project (`GET projects/{id}/teams`). */
@Serializable
data class ProjectTeamShareDto(
    val id: Long = 0,
    val name: String = "",
    val permission: Int = 0,
    val members: List<UserDto>? = null,
)

@Serializable
data class ShareUserDto(val username: String, val permission: Int)

@Serializable
data class PermissionDto(val permission: Int)

@Serializable
data class AssigneeDto(@SerialName("user_id") val userId: Long)

/** The part of a single-task read that says whether you follow the task (`task.subscription`). */
@Serializable
data class TaskSubscriptionReadDto(val subscription: SubscriptionDto? = null)

@Serializable
data class SubscriptionDto(val id: Long = 0)
