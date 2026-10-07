package com.rendyhd.vicu.data.mapper

import com.rendyhd.vicu.auth.authTestJson
import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.util.Constants
import kotlin.test.Test
import kotlin.test.assertEquals

class TaskMapperEditedFieldsTest {

    private val mapper = TaskMapper(authTestJson)

    private val cached = TaskEntity(
        id = 7,
        title = "Cached",
        description = "Cached description",
        dueDate = "2026-10-08T21:59:59Z",
        priority = 1,
        projectId = 3,
        position = 99.5,
        taskIndex = 12,
        kanbanPosition = 5.0,
        created = "2026-10-01T00:00:00Z",
        updated = "2026-10-02T00:00:00Z",
        createdById = 4,
        createdByUsername = "owner",
        labelsJson = """[{"id":1,"title":"x"}]""",
        attachmentsJson = """[{"id":2,"task_id":7}]""",
        relatedTasksJson = """{"subtask":[{"id":8,"title":"Child"}]}""",
        remindersJson = """[{"reminder":"2026-10-08T08:00:00Z","relative_period":0}]""",
    )

    @Test
    fun `edited fields replace the scalars and reminders`() = with(mapper) {
        val edited = cached.toDomain().copy(
            title = "Edited",
            description = "",
            priority = 4,
            projectId = 9,
            dueDate = "",
            reminders = emptyList(),
            done = true,
        )

        val result = cached.withEditedFields(edited)

        assertEquals("Edited", result.title)
        assertEquals("", result.description)
        assertEquals(4, result.priority)
        assertEquals(9, result.projectId)
        assertEquals(Constants.NULL_DATE_STRING, result.dueDate)
        assertEquals("[]", result.remindersJson)
        assertEquals(true, result.done)
    }

    @Test
    fun `edited fields leave relations attachments labels and bookkeeping alone`() = with(mapper) {
        val result = cached.withEditedFields(cached.toDomain().copy(title = "Edited"))

        assertEquals(cached.relatedTasksJson, result.relatedTasksJson)
        assertEquals(cached.attachmentsJson, result.attachmentsJson)
        assertEquals(cached.labelsJson, result.labelsJson)
        assertEquals(cached.position, result.position)
        assertEquals(cached.taskIndex, result.taskIndex)
        assertEquals(cached.kanbanPosition, result.kanbanPosition)
        assertEquals(cached.created, result.created)
        assertEquals(cached.updated, result.updated)
        assertEquals(cached.createdById, result.createdById)
        assertEquals(cached.createdByUsername, result.createdByUsername)
        assertEquals(cached.id, result.id)
    }

    @Test
    fun `done state changes only done and done_at`() = with(mapper) {
        val result = cached.withDoneState(done = true, doneAt = "2026-10-06T10:00:00Z")

        assertEquals(cached.copy(done = true, doneAt = "2026-10-06T10:00:00Z"), result)
        assertEquals(Constants.NULL_DATE_STRING, result.withDoneState(done = false, doneAt = "").doneAt)
    }
}
