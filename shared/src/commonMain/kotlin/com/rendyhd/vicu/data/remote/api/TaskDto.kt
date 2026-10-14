package com.rendyhd.vicu.data.remote.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TaskDto(
    val id: Long = 0,
    val title: String = "",
    val description: String = "",
    val done: Boolean = false,
    @SerialName("done_at") val doneAt: String = "",
    @SerialName("due_date") val dueDate: String = "",
    val priority: Int = 0,
    @SerialName("project_id") val projectId: Long = 0,
    @SerialName("repeat_after") val repeatAfter: Long = 0,
    @SerialName("repeat_mode") val repeatMode: Int = 0,
    @SerialName("start_date") val startDate: String = "",
    @SerialName("end_date") val endDate: String = "",
    @SerialName("hex_color") val hexColor: String = "",
    @SerialName("percent_done") val percentDone: Double = 0.0,
    val index: Long = 0,
    val position: Double = 0.0,
    @SerialName("kanban_position") val kanbanPosition: Double = 0.0,
    @SerialName("bucket_id") val bucketId: Long = 0,
    val created: String = "",
    val updated: String = "",
    @SerialName("created_by") val createdBy: UserDto? = null,
    val labels: List<LabelDto>? = null,
    val reminders: List<TaskReminderDto>? = null,
    val attachments: List<AttachmentDto>? = null,
    @SerialName("related_tasks") val relatedTasks: Map<String, List<TaskDto>>? = null,
    @SerialName("is_favorite") val isFavorite: Boolean = false,
    /** Read only: changed through the assignee endpoints, never a merge patch (cross-app semantics 9.2). */
    val assignees: List<UserDto>? = null,
    /** Only with `expand=comment_count`; absent means 0. */
    @SerialName("comment_count") val commentCount: Int? = null,
    /** Only with `expand=is_unread`; absent means false. */
    @SerialName("is_unread") val isUnread: Boolean? = null,
)

/** Minimal payload for PUT /projects/{id}/tasks — matches desktop CreateTaskPayload */
@Serializable
data class CreateTaskDto(
    val title: String,
    val description: String = "",
    val done: Boolean = false,
    @SerialName("due_date") val dueDate: String? = null,
    @SerialName("start_date") val startDate: String? = null,
    val priority: Int = 0,
    @SerialName("repeat_after") val repeatAfter: Long = 0,
    @SerialName("repeat_mode") val repeatMode: Int = 0,
    val reminders: List<TaskReminderDto>? = null,
)
