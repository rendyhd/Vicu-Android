package com.rendyhd.vicu.ui.screens.taskdetail

import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskReminder
import kotlin.test.Test
import kotlin.test.assertEquals

class TaskDetailReconciliationTest {

    @Test
    fun pristineEditorAdoptsDescriptionOnlyRemoteUpdate() {
        val baseline = task(description = "<p>one</p>")
        val incoming = baseline.copy(description = "<p>one</p><p>two</p>", updated = "later")

        val reconciled = reconcileTaskEditor(baseline, baseline, incoming)

        assertEquals(incoming, reconciled)
    }

    @Test
    fun localDescriptionIsPreservedWhileUntouchedRemoteFieldsAdvance() {
        val baseline = task(description = "old", title = "Old title", priority = 1)
        val current = baseline.copy(description = "local draft")
        val incoming = baseline.copy(description = "remote notes", title = "Remote title", priority = 4)

        val reconciled = reconcileTaskEditor(current, baseline, incoming)

        assertEquals("local draft", reconciled.description)
        assertEquals("Remote title", reconciled.title)
        assertEquals(4, reconciled.priority)
    }

    @Test
    fun everyLocallyEditedWritableFieldSurvivesRoomEmission() {
        val baseline = task()
        val local = baseline.copy(
            title = "local",
            done = true,
            dueDate = "2030-01-01T00:00:00Z",
            projectId = 9,
            repeatAfter = 2,
            repeatMode = 1,
            reminders = listOf(TaskReminder(reminder = "2030-01-01T08:00:00Z")),
            isFavorite = true,
        )
        val incoming = baseline.copy(title = "remote", priority = 3, updated = "later")

        val reconciled = reconcileTaskEditor(local, baseline, incoming)

        assertEquals("local", reconciled.title)
        assertEquals(true, reconciled.done)
        assertEquals("2030-01-01T00:00:00Z", reconciled.dueDate)
        assertEquals(9, reconciled.projectId)
        assertEquals(2, reconciled.repeatAfter)
        assertEquals(1, reconciled.repeatMode)
        assertEquals(local.reminders, reconciled.reminders)
        assertEquals(true, reconciled.isFavorite)
        assertEquals(3, reconciled.priority)
        assertEquals("later", reconciled.updated)
    }

    @Test
    fun localRepeatAfterEditPreservesTheWholeRecurrencePair() {
        val baseline = task().copy(repeatAfter = 86_400L, repeatMode = 0)
        val local = baseline.copy(repeatAfter = 604_800L)
        val incoming = baseline.copy(repeatMode = 2, updated = "later")

        val reconciled = reconcileTaskEditor(local, baseline, incoming)

        assertEquals(604_800L, reconciled.repeatAfter)
        assertEquals(0, reconciled.repeatMode)
    }

    @Test
    fun localRepeatModeEditPreservesTheWholeRecurrencePair() {
        val baseline = task().copy(repeatAfter = 86_400L, repeatMode = 0)
        val local = baseline.copy(repeatMode = 2)
        val incoming = baseline.copy(repeatAfter = 604_800L, updated = "later")

        val reconciled = reconcileTaskEditor(local, baseline, incoming)

        assertEquals(86_400L, reconciled.repeatAfter)
        assertEquals(2, reconciled.repeatMode)
    }

    @Test
    fun pristineRecurrenceAdoptsBothIncomingFields() {
        val baseline = task().copy(repeatAfter = 86_400L, repeatMode = 0)
        val incoming = baseline.copy(repeatAfter = 0L, repeatMode = 1, updated = "later")

        val reconciled = reconcileTaskEditor(baseline, baseline, incoming)

        assertEquals(0L, reconciled.repeatAfter)
        assertEquals(1, reconciled.repeatMode)
    }

    private fun task(
        description: String = "notes",
        title: String = "Task",
        priority: Int = 0,
    ) = Task(
        id = 18,
        title = title,
        description = description,
        priority = priority,
        projectId = 1,
    )
}
