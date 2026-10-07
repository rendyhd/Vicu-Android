package com.rendyhd.vicu.ui.screens.taskdetail

import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskReminder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TaskDetailDraftTest {
    private val base = Task(id = 7, title = "Base", description = "<p>base</p>", priority = 1, projectId = 3)

    private fun draft(edited: Task, from: Task = base) = TaskDetailDraft(
        taskId = edited.id,
        edited = edited.draftFields(),
        base = from.draftFields(),
        manuallyEditedTypes = listOf("PRIORITY"),
    )

    @Test
    fun `a draft survives the encoded round trip`() {
        val original = draft(
            base.copy(
                title = "Edited \"title\" with unicode é",
                reminders = listOf(TaskReminder(reminder = "2026-10-08T09:00:00Z")),
            ),
        )

        val decoded = TaskDetailDraftCodec.decode(TaskDetailDraftCodec.encode(original))

        assertEquals(original, decoded)
    }

    @Test
    fun `garbage and blank input decode to nothing`() {
        assertNull(TaskDetailDraftCodec.decode(null))
        assertNull(TaskDetailDraftCodec.decode(""))
        assertNull(TaskDetailDraftCodec.decode("not json"))
        assertNull(TaskDetailDraftCodec.decode("""{"taskId":1}"""))
    }

    @Test
    fun `a draft too large for instance state is not stored`() {
        val huge = draft(base.copy(description = "x".repeat(TaskDetailDraftCodec.MAX_ENCODED_CHARS + 1)))

        assertNull(TaskDetailDraftCodec.encode(huge))
        assertNotNull(TaskDetailDraftCodec.encode(draft(base.copy(description = "x".repeat(1_000)))))
    }

    @Test
    fun `a restored draft keeps what the user changed and adopts the rest`() {
        // The user edited the title and the description; meanwhile another device changed the
        // priority and the title of the same task.
        val edited = base.copy(title = "My title", description = "<p>my text</p>")
        val serverNow = base.copy(title = "Their title", priority = 4)

        val applied = applyDraft(draft(edited), serverNow)

        assertEquals("My title", applied.title)
        assertEquals("<p>my text</p>", applied.description)
        assertEquals(4, applied.priority)
    }
}
