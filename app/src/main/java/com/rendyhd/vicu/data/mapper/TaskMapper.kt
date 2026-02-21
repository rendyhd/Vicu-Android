package com.rendyhd.vicu.data.mapper

import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.data.remote.api.AttachmentDto
import com.rendyhd.vicu.data.remote.api.CreateTaskDto
import com.rendyhd.vicu.data.remote.api.LabelDto
import com.rendyhd.vicu.data.remote.api.TaskDto
import com.rendyhd.vicu.data.remote.api.TaskReminderDto
import com.rendyhd.vicu.data.remote.api.UserDto
import com.rendyhd.vicu.domain.model.Attachment
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskReminder
import com.rendyhd.vicu.domain.model.User
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import com.rendyhd.vicu.util.Constants
import com.rendyhd.vicu.util.DateUtils
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskMapper @Inject constructor(private val json: Json) {

    fun TaskDto.toEntity(): TaskEntity = TaskEntity(
        id = id,
        title = title,
        description = description,
        done = done,
        doneAt = DateUtils.normalizeToUtc(doneAt),
        dueDate = DateUtils.normalizeToUtc(dueDate),
        priority = priority,
        projectId = projectId,
        repeatAfter = repeatAfter,
        repeatMode = repeatMode,
        startDate = DateUtils.normalizeToUtc(startDate),
        endDate = DateUtils.normalizeToUtc(endDate),
        hexColor = hexColor,
        percentDone = percentDone,
        taskIndex = index,
        position = position,
        kanbanPosition = kanbanPosition,
        bucketId = bucketId,
        created = DateUtils.normalizeToUtc(created),
        updated = DateUtils.normalizeToUtc(updated),
        createdById = createdBy?.id ?: 0,
        createdByUsername = createdBy?.username ?: "",
        labelsJson = json.encodeToString(
            ListSerializer(LabelDto.serializer()),
            labels ?: emptyList()
        ),
        remindersJson = json.encodeToString(
            ListSerializer(TaskReminderDto.serializer()),
            reminders ?: emptyList()
        ),
        attachmentsJson = json.encodeToString(
            ListSerializer(AttachmentDto.serializer()),
            attachments ?: emptyList()
        ),
        relatedTasksJson = json.encodeToString(
            MapSerializer(
                String.serializer(),
                ListSerializer(TaskDto.serializer())
            ),
            relatedTasks ?: emptyMap()
        ),
        isFavorite = isFavorite,
    )

    fun TaskEntity.toDomain(): Task {
        val labelDtos: List<LabelDto> = try {
            json.decodeFromString(
                ListSerializer(LabelDto.serializer()),
                labelsJson
            )
        } catch (_: Exception) { emptyList() }

        val reminderDtos: List<TaskReminderDto> = try {
            json.decodeFromString(
                ListSerializer(TaskReminderDto.serializer()),
                remindersJson
            )
        } catch (_: Exception) { emptyList() }

        val attachmentDtos: List<AttachmentDto> = try {
            json.decodeFromString(
                ListSerializer(AttachmentDto.serializer()),
                attachmentsJson
            )
        } catch (_: Exception) { emptyList() }

        val relatedTaskDtos: Map<String, List<TaskDto>> = try {
            json.decodeFromString(
                MapSerializer(String.serializer(), ListSerializer(TaskDto.serializer())),
                relatedTasksJson
            )
        } catch (_: Exception) { emptyMap() }

        return Task(
            id = id,
            title = title,
            description = description,
            done = done,
            doneAt = doneAt,
            dueDate = dueDate,
            priority = priority,
            projectId = projectId,
            repeatAfter = repeatAfter,
            repeatMode = repeatMode,
            startDate = startDate,
            endDate = endDate,
            hexColor = hexColor,
            percentDone = percentDone,
            index = taskIndex,
            position = position,
            kanbanPosition = kanbanPosition,
            bucketId = bucketId,
            created = created,
            updated = updated,
            createdBy = User(id = createdById, username = createdByUsername, name = ""),
            labels = labelDtos.map { it.toDomainLabel() },
            reminders = reminderDtos.map { it.toDomain() },
            attachments = attachmentDtos.map { it.toDomainAttachment() },
            relatedTasks = relatedTaskDtos.mapValues { (_, taskDtos) ->
                taskDtos.map { dto ->
                    Task(
                        id = dto.id,
                        title = dto.title,
                        description = dto.description,
                        done = dto.done,
                        doneAt = dto.doneAt,
                        dueDate = dto.dueDate,
                        priority = dto.priority,
                        projectId = dto.projectId,
                        repeatAfter = dto.repeatAfter,
                        repeatMode = dto.repeatMode,
                        created = dto.created,
                        updated = dto.updated,
                    )
                }
            },
            isFavorite = isFavorite,
        )
    }

    private fun dateOrNull(value: String): String =
        if (value.isBlank()) Constants.NULL_DATE_STRING else value

    private fun dateOrNullable(value: String): String? =
        if (value.isBlank() || DateUtils.isNullDate(value)) null else value

    fun Task.toCreateDto(): CreateTaskDto = CreateTaskDto(
        title = title,
        description = description,
        dueDate = dateOrNullable(dueDate),
        startDate = dateOrNullable(startDate),
        priority = priority,
        reminders = reminders.map { it.toDto() }.ifEmpty { null },
    )

    fun Task.toDto(): TaskDto = TaskDto(
        id = id,
        title = title,
        description = description,
        done = done,
        doneAt = dateOrNull(doneAt),
        dueDate = dateOrNull(dueDate),
        priority = priority,
        projectId = projectId,
        repeatAfter = repeatAfter,
        repeatMode = repeatMode,
        startDate = dateOrNull(startDate),
        endDate = dateOrNull(endDate),
        hexColor = hexColor,
        percentDone = percentDone,
        index = index,
        position = position,
        kanbanPosition = kanbanPosition,
        bucketId = bucketId,
        created = dateOrNull(created),
        updated = dateOrNull(updated),
        // Don't send created_by — it's read-only/server-managed.
        // Round-trip loses email/created/updated fields, causing HTTP 400.
        createdBy = null,
        labels = labels.map { it.toLabelDto() },
        reminders = reminders.map { it.toDto() },
        isFavorite = isFavorite,
    )

    private fun TaskReminderDto.toDomain(): TaskReminder = TaskReminder(
        reminder = reminder,
        relativePeriod = relativePeriod,
        relativeTo = relativeTo,
    )

    private fun TaskReminder.toDto(): TaskReminderDto = TaskReminderDto(
        reminder = reminder,
        relativePeriod = relativePeriod,
        relativeTo = relativeTo,
    )

    private fun AttachmentDto.toDomainAttachment(): Attachment = Attachment(
        id = id,
        taskId = taskId,
        fileName = file?.name ?: "",
        mimeType = file?.mime ?: "",
        fileSize = file?.size ?: 0,
        createdAt = created,
    )

    private fun LabelDto.toDomainLabel(): Label = Label(
        id = id,
        title = title,
        description = description,
        hexColor = hexColor,
        createdById = createdBy?.id ?: 0,
        created = created,
        updated = updated,
    )

    private fun Label.toLabelDto(): LabelDto = LabelDto(
        id = id,
        title = title,
        description = description,
        hexColor = hexColor,
    )
}
