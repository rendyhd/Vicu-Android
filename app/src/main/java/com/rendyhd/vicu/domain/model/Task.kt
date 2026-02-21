package com.rendyhd.vicu.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Task(
    val id: Long,
    val title: String,
    val description: String = "",
    val done: Boolean = false,
    val doneAt: String = "",
    val dueDate: String = "",
    val priority: Int = 0,
    val projectId: Long = 0,
    val repeatAfter: Long = 0,
    val repeatMode: Int = 0,
    val startDate: String = "",
    val endDate: String = "",
    val hexColor: String = "",
    val percentDone: Double = 0.0,
    val index: Long = 0,
    val position: Double = 0.0,
    val kanbanPosition: Double = 0.0,
    val bucketId: Long = 0,
    val created: String = "",
    val updated: String = "",
    val createdBy: User = User(0, "", ""),
    val labels: List<Label> = emptyList(),
    val reminders: List<TaskReminder> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val relatedTasks: Map<String, List<Task>> = emptyMap(),
    val isFavorite: Boolean = false,
)
