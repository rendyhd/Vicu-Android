package com.rendyhd.vicu.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.rendyhd.vicu.util.CustomListEnvelope

@Entity(
    tableName = "tasks",
    indices = [
        Index(value = ["projectId"]),
        Index(value = ["done"]),
        Index(value = ["dueDate"]),
        Index(value = ["isMetadata"]),
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
    /**
     * True for a hidden sync-metadata task (a routine carrier or archive part, a custom-list
     * carrier): the description holds one of the markers. It is worked out once, when the row is
     * written, so the list queries leave these rows out in SQL instead of every emission of every
     * live list scanning every description on the main thread (A-UI-19).
     *
     * A copy that changes [description] must carry the new value along (see
     * [com.rendyhd.vicu.data.mapper.TaskMapper]); the migration to version 3 filled the column in
     * with a looser SQL `LIKE`, and the next write of such a row corrects any false positive.
     */
    @ColumnInfo(defaultValue = "0")
    val isMetadata: Boolean = CustomListEnvelope.isAnyMetadataTask(description),
)
