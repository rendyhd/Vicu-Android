package com.rendyhd.vicu.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tasks",
    indices = [
        Index(value = ["projectId"]),
        Index(value = ["done"]),
        Index(value = ["dueDate"]),
    ]
)
data class TaskEntity(
    @PrimaryKey val id: Long,
    val title: String = "",
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
    @ColumnInfo(name = "task_index") val taskIndex: Long = 0,
    val position: Double = 0.0,
    val kanbanPosition: Double = 0.0,
    val bucketId: Long = 0,
    val created: String = "",
    val updated: String = "",
    val createdById: Long = 0,
    val createdByUsername: String = "",
    val labelsJson: String = "[]",
    val remindersJson: String = "[]",
    val attachmentsJson: String = "[]",
    val relatedTasksJson: String = "{}",
    val isFavorite: Boolean = false,
)
