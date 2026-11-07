package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskReminder
import com.rendyhd.vicu.util.CrossAppFixture.local
import com.rendyhd.vicu.util.CrossAppFixture.zones
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs the `taskTemplates` vectors of `test-fixtures/cross-app-semantics-v1.json` (contract section 18). */
class TaskTemplatesFixtureTest {
    @Serializable
    private data class SourceReminder(
        val reminder: String = "",
        @SerialName("relative_to") val relativeTo: String = "",
        @SerialName("relative_period") val relativePeriod: Long = 0,
    )

    @Serializable
    private data class Source(
        val title: String,
        @SerialName("project_id") val projectId: Long = 0,
        val notes: String = "",
        @SerialName("label_ids") val labelIds: List<Long> = emptyList(),
        val priority: Int = 0,
        val due: String? = null,
        val done: Boolean = false,
        val start: String? = null,
        val end: String? = null,
        @SerialName("repeat_after") val repeatAfter: Long = 0,
        @SerialName("repeat_mode") val repeatMode: Int = 0,
        val reminders: List<SourceReminder> = emptyList(),
        val subtasks: List<Source> = emptyList(),
    )

    @Serializable
    private data class Capture(val name: String, val today: String, val task: Source, val expect: JsonElement)

    @Serializable
    private data class ApplyExpect(
        val title: String,
        @SerialName("project_id") val projectId: Long,
        val due: String? = null,
        @SerialName("label_ids") val labelIds: List<Long>,
        val reminders: List<TemplateReminder>,
    )

    @Serializable
    private data class Apply(
        val name: String,
        val today: String,
        val projects: List<Long>,
        val labels: List<Long>,
        @SerialName("fallback_project") val fallbackProject: Long,
        val template: JsonElement,
        val expect: ApplyExpect,
    )

    @Serializable
    private data class Read(val name: String, val value: JsonElement = JsonNull, val isTemplate: Boolean)

    @Serializable
    private data class Vectors(val capture: List<Capture>, val apply: List<Apply>, val read: List<Read>)

    @Serializable
    private data class Root(val taskTemplates: Vectors)

    private val vectors: Vectors = Json { ignoreUnknownKeys = true }
        .decodeFromString<Root>(CrossAppFixture.readTestFixture("cross-app-semantics-v1.json"))
        .taskTemplates

    private fun Source.toDomain(zone: TimeZone): TemplateSource = TemplateSource(
        Task(
            id = 1,
            title = title,
            description = notes,
            done = done,
            dueDate = due?.let { local(it, zone).toString() } ?: Constants.NULL_DATE_STRING,
            startDate = start?.let { local(it, zone).toString() }.orEmpty(),
            endDate = end?.let { local(it, zone).toString() }.orEmpty(),
            priority = priority,
            projectId = projectId,
            repeatAfter = repeatAfter,
            repeatMode = repeatMode,
            labels = labelIds.map { Label(id = it, title = "Label $it") },
            reminders = reminders.map {
                TaskReminder(
                    reminder = if (it.reminder.isEmpty()) "" else local(it.reminder, zone).toString(),
                    relativePeriod = it.relativePeriod,
                    relativeTo = it.relativeTo,
                )
            },
        ),
        subtasks.map { it.toDomain(zone) },
    )

    @Test
    fun capture() {
        assertTrue(vectors.capture.size >= 10)
        for (zone in zones) {
            for (vector in vectors.capture) {
                val template = TaskTemplates.capture(vector.task.toDomain(zone), LocalDate.parse(vector.today), zone)
                assertEquals(vector.expect, TaskTemplates.encode(template), "${vector.name} in $zone")
            }
        }
    }

    @Test
    fun apply() {
        assertTrue(vectors.apply.size >= 6)
        for (zone in zones) {
            for (vector in vectors.apply) {
                val template = assertNotNull(TaskTemplates.read("t", vector.template), vector.name)
                val project = TaskTemplates.projectFor(template, vector.projects.toSet(), vector.fallbackProject)
                val task = TaskTemplates.taskOf(template, project, LocalDate.parse(vector.today), zone)
                assertEquals(vector.expect.title, task.title, vector.name)
                assertEquals(vector.expect.projectId, task.projectId, vector.name)
                assertEquals(vector.expect.due?.let { local(it, zone).toString() }.orEmpty(), task.dueDate, "${vector.name} in $zone")
                assertEquals(vector.expect.labelIds, template.labels.filter { it in vector.labels }, vector.name)
                assertEquals(vector.expect.reminders, TaskTemplates.relativeReminders(task.reminders), vector.name)
            }
        }
    }

    @Test
    fun read() {
        for (vector in vectors.read) {
            assertEquals(vector.isTemplate, TaskTemplates.read("t", vector.value) != null, vector.name)
        }
    }

    @Test
    fun anEditKeepsUnknownFieldsAndDropsWhatItCleared() {
        val raw = buildJsonObject {
            put("title", "A")
            put("priority", 3)
            put("from_the_future", 1)
        }
        val read = assertNotNull(TaskTemplates.read("t", raw))
        val written = TaskTemplates.encode(read.copy(priority = 0)) as JsonObject
        assertEquals(JsonPrimitive(1), written["from_the_future"])
        assertNull(written["priority"])
    }

    @Test
    fun templatesAreListedByWhenTheyWereSaved() {
        val key = com.rendyhd.vicu.domain.model.SyncedPrefKeys::templateOf
        val entries = mapOf(
            key("b") to buildJsonObject { put("title", "Second"); put("created_ms", 2) },
            key("a") to buildJsonObject { put("title", "Third"); put("created_ms", 3) },
            key("c") to buildJsonObject { put("title", "First"); put("created_ms", 1) },
            key("gone") to null,
            "other" to buildJsonObject { put("title", "Not a template") },
        )
        assertEquals(listOf("First", "Second", "Third"), TaskTemplates.fromEntries(entries).map { it.title })
    }

    @Test
    fun anEditKeepsTheSubtasksItKeptByTitle() {
        val kept = TaskTemplate(title = "Pack", subtasks = listOf(TaskTemplate(title = "Socks")))
        val result = TaskTemplates.withSubtaskTitles(listOf(TaskTemplate(title = "Book"), kept), listOf("Pack", "New"))
        assertEquals(listOf(kept, TaskTemplate(title = "New")), result)
    }

    @Test
    fun tooLarge() {
        assertFalse(TaskTemplates.isTooLarge(TaskTemplate(title = "A")))
        assertTrue(TaskTemplates.isTooLarge(TaskTemplate(title = "A", notes = "x".repeat(TaskTemplates.MAX_BYTES))))
    }
}
