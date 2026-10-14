package com.rendyhd.vicu.data.remote.api

import com.rendyhd.vicu.data.local.dao.normalizeQueuedPatchPayload
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskReminder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MergePatchesTest {

    @Test
    fun `issue 24 edit changes only description and priority`() {
        val original = Task(
            id = 24,
            title = "Persistence regression",
            description = "Initial description",
            priority = 4,
            dueDate = "2026-07-25T21:59:59Z",
            projectId = 7,
            created = "2026-07-24T12:00:00Z",
            updated = "2026-07-24T12:00:00Z",
        )

        val patch = MergePatches.task(
            original,
            original.copy(description = "Persisted description", priority = 2),
        )

        assertEquals(setOf("description", "priority"), patch.keys)
        assertEquals(JsonPrimitive("Persisted description"), patch["description"])
        assertEquals(JsonPrimitive(2), patch["priority"])
        assertFalse("title" in patch)
        assertFalse("due_date" in patch)
    }

    @Test
    fun `task patch preserves explicit false zero empty and null`() {
        val original = Task(
            id = 99,
            title = "Writable fields",
            description = "non-empty",
            done = true,
            dueDate = "2026-08-01T10:00:00Z",
            priority = 3,
        )

        val patch = MergePatches.task(
            original,
            original.copy(
                description = "",
                done = false,
                dueDate = "",
                priority = 0,
            ),
        )

        assertEquals(JsonPrimitive(""), patch["description"])
        assertEquals(JsonPrimitive(false), patch["done"])
        assertEquals(JsonNull, patch["due_date"])
        assertEquals(JsonPrimitive(0), patch["priority"])
    }

    @Test
    fun `task patch sets both recurrence fields atomically`() {
        val original = Task(id = 1, title = "Repeat", repeatAfter = 0, repeatMode = 0)

        val patch = MergePatches.task(
            original,
            original.copy(repeatAfter = 604_800L, repeatMode = 2),
        )

        assertEquals(setOf("repeat_after", "repeat_mode"), patch.keys)
        assertEquals(JsonPrimitive(604_800L), patch["repeat_after"])
        assertEquals(JsonPrimitive(2), patch["repeat_mode"])
    }

    @Test
    fun `task patch clears recurrence with explicit zeroes`() {
        val original = Task(id = 1, title = "Repeat", repeatAfter = 604_800L, repeatMode = 2)

        val patch = MergePatches.task(
            original,
            original.copy(repeatAfter = 0, repeatMode = 0),
        )

        assertEquals(JsonPrimitive(0L), patch["repeat_after"])
        assertEquals(JsonPrimitive(0), patch["repeat_mode"])
    }

    @Test
    fun `task patch never contains response-only fields`() {
        val patch = MergePatches.task(
            previous = null,
            current = Task(
                id = 123,
                title = "Only writable data",
                created = "2026-07-24T12:00:00Z",
                updated = "2026-07-24T13:00:00Z",
                doneAt = "2026-07-24T13:00:00Z",
                position = 65_536.0,
            ),
        )

        assertTrue(patch.keys.intersect(MergePatches.taskReadOnlyFields).isEmpty())
    }

    @Test
    fun `project and label patches contain only changed writable fields`() {
        val project = Project(id = 1, title = "Before", created = "server-owned")
        val label = Label(id = 2, title = "Before", created = "server-owned")

        assertEquals(
            setOf("title"),
            MergePatches.project(project, project.copy(title = "After")).keys,
        )
        assertEquals(
            setOf("title"),
            MergePatches.label(label, label.copy(title = "After")).keys,
        )
    }

    @Test
    fun `project archive patch changes only archive state`() {
        val original = Project(
            id = 7,
            title = "Keep title",
            description = "Keep description",
            hexColor = "#3498db",
            parentProjectId = 3,
            position = 65_536.0,
        )

        val patch = MergePatches.project(original, original.copy(isArchived = true))

        assertEquals(setOf("is_archived"), patch.keys)
        assertEquals(JsonPrimitive(true), patch["is_archived"])
    }

    @Test
    fun `issue 30 relative reminder patch omits blank reminder date`() {
        val original = Task(id = 30, title = "Reminder", dueDate = "2026-10-05T09:00:00Z")

        val patch = MergePatches.task(
            original,
            original.copy(
                reminders = listOf(TaskReminder(relativePeriod = -900, relativeTo = "due_date")),
            ),
        )

        val reminder = (patch["reminders"] as JsonArray).single() as JsonObject
        assertEquals(setOf("relative_period", "relative_to"), reminder.keys)
        assertEquals(JsonPrimitive(-900L), reminder["relative_period"])
        assertEquals(JsonPrimitive("due_date"), reminder["relative_to"])
    }

    @Test
    fun `issue 30 absolute reminder patch omits blank relative_to`() {
        val original = Task(id = 30, title = "Reminder")

        val patch = MergePatches.task(
            original,
            original.copy(reminders = listOf(TaskReminder(reminder = "2026-10-05T08:00:00Z"))),
        )

        val reminder = (patch["reminders"] as JsonArray).single() as JsonObject
        assertEquals(setOf("reminder", "relative_period"), reminder.keys)
        assertEquals(JsonPrimitive("2026-10-05T08:00:00Z"), reminder["reminder"])
    }

    @Test
    fun `issue 30 queued patches with blank reminder fields are sanitized on replay`() {
        val legacy = """{"reminders":[""" +
            """{"reminder":"","relative_period":-900,"relative_to":"due_date"},""" +
            """{"reminder":"2026-10-05T08:00:00Z","relative_period":0,"relative_to":""}]}"""

        val normalized = Json.parseToJsonElement(normalizeQueuedPatchPayload("task", legacy)) as JsonObject

        val reminders = (normalized["reminders"] as JsonArray).map { it as JsonObject }
        assertEquals(setOf("relative_period", "relative_to"), reminders[0].keys)
        assertEquals(setOf("reminder", "relative_period"), reminders[1].keys)
    }

    @Test
    fun `issue 30 reminder dto never encodes blank fields`() {
        val json = Json { encodeDefaults = true }

        val relative = json.encodeToString(
            TaskReminderDto.serializer(),
            TaskReminderDto(relativePeriod = -900, relativeTo = "due_date"),
        )
        val absolute = json.encodeToString(
            TaskReminderDto.serializer(),
            TaskReminderDto(reminder = "2026-10-05T08:00:00Z"),
        )

        assertEquals("""{"relative_period":-900,"relative_to":"due_date"}""", relative)
        assertEquals("""{"reminder":"2026-10-05T08:00:00Z","relative_period":0}""", absolute)
    }
}
