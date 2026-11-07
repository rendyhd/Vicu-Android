package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.SyncedPrefKeys
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskReminder
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** When a task from a template is due: [days] from today, at [time] (`"HH:mm"`) or date-only. */
@Serializable
data class TemplateDue(val days: Int, val time: String? = null)

@Serializable
data class TemplateReminder(
    @SerialName("relative_period") val relativePeriod: Long,
    @SerialName("relative_to") val relativeTo: String,
)

/**
 * A task to start from (docs/cross-app-semantics-v1.md section 18), one `template.<id>` entry of
 * the synced preferences. Subtasks have the same shape, without [projectId] and [createdMs].
 */
@Serializable
data class TaskTemplate(
    val title: String,
    @SerialName("project_id") val projectId: Long? = null,
    val notes: String = "",
    val labels: List<Long> = emptyList(),
    val priority: Int = 0,
    val due: TemplateDue? = null,
    @SerialName("repeat_after") val repeatAfter: Long = 0,
    @SerialName("repeat_mode") val repeatMode: Int = 0,
    val reminders: List<TemplateReminder> = emptyList(),
    val subtasks: List<TaskTemplate> = emptyList(),
    @SerialName("created_ms") val createdMs: Long = 0,
    /** The id of its entry. */
    @Transient val id: String = "",
    /** The entry as read, so fields of a newer version outlive an edit here. */
    @Transient val raw: JsonObject = JsonObject(emptyMap()),
)

/** A task with its subtasks, as far as this device knows them. */
class TemplateSource(val task: Task, val subtasks: List<TemplateSource> = emptyList())

object TaskTemplates {
    /** Section 18.1: the carrier is one description. */
    const val MAX_BYTES = 16 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    private val KNOWN_FIELDS = setOf(
        "title", "project_id", "notes", "labels", "priority", "due", "repeat_after", "repeat_mode",
        "reminders", "subtasks", "created_ms",
    )

    private val IMAGE_TOKEN = Regex("""\s*\[\[image(-pending)?:[^\]]*]]""")
    private val IMAGE_TAG = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)

    /** The template [value] holds, or null when it is not one: it is then ignored and kept. */
    fun read(id: String, value: JsonElement?): TaskTemplate? {
        val entry = value as? JsonObject ?: return null
        val template = runCatching { json.decodeFromJsonElement(TaskTemplate.serializer(), entry) }.getOrNull() ?: return null
        if (template.title.isBlank()) return null
        return template.copy(id = id, raw = entry)
    }

    fun encode(template: TaskTemplate): JsonElement {
        val written = json.encodeToJsonElement(TaskTemplate.serializer(), template).jsonObject
        return JsonObject(template.raw.filterKeys { it !in KNOWN_FIELDS } + written)
    }

    fun isTooLarge(template: TaskTemplate): Boolean =
        encode(template).toString().encodeToByteArray().size > MAX_BYTES

    /** The account's templates, oldest first. */
    fun fromEntries(entries: Map<String, JsonElement?>): List<TaskTemplate> =
        entries.mapNotNull { (key, value) ->
            SyncedPrefKeys.templateId(key)?.let { read(it, value) }
        }.sortedWith(compareBy({ it.createdMs }, { it.id }))

    /** What [source] saves as a template (section 18.1); the caller gives it an id and a time. */
    fun capture(source: TemplateSource, today: LocalDate, zone: TimeZone): TaskTemplate =
        node(source, today, zone).copy(projectId = source.task.projectId)

    private fun node(source: TemplateSource, today: LocalDate, zone: TimeZone): TaskTemplate {
        val task = source.task
        val due = dueOf(task.dueDate, today, zone)
        return TaskTemplate(
            title = task.title,
            notes = withoutImages(task.description),
            labels = task.labels.map { it.id },
            priority = task.priority,
            due = due,
            repeatAfter = task.repeatAfter,
            repeatMode = task.repeatMode,
            reminders = if (due == null) emptyList() else relativeReminders(task.reminders),
            subtasks = source.subtasks.map { node(it, today, zone) },
        )
    }

    /** A due date as the template keeps it: days from today, never below 0, and its time of day. */
    fun dueOf(dueDate: String, today: LocalDate, zone: TimeZone): TemplateDue? {
        val date = DueDates.localDateOf(dueDate, zone) ?: return null
        val time = if (DueDates.isDateOnly(dueDate, zone)) {
            null
        } else {
            val local = Instant.parse(dueDate).toLocalDateTime(zone).time
            "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
        }
        return TemplateDue(today.daysUntil(date).coerceAtLeast(0), time)
    }

    /** Only reminders that count from the due date travel; the others name a moment or a block. */
    fun relativeReminders(reminders: List<TaskReminder>): List<TemplateReminder> =
        reminders.filter { it.relativeTo == "due_date" }.map { TemplateReminder(it.relativePeriod, it.relativeTo) }

    private fun withoutImages(notes: String): String =
        notes.replace(IMAGE_TAG, "").replace(IMAGE_TOKEN, "").trim()

    /** The due date a task made today gets, or the empty date. */
    fun dueDateOf(due: TemplateDue?, today: LocalDate, zone: TimeZone): String {
        if (due == null) return ""
        val date = today.plus(due.days.coerceAtLeast(0), DateTimeUnit.DAY)
        val time = due.time?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            ?: return DueDates.dateOnlyDue(date, zone).toString()
        return LocalDateTime(date, time).toInstant(zone).toString()
    }

    /** The new task of [template]; its labels and subtasks are added once it exists. */
    fun taskOf(template: TaskTemplate, projectId: Long, today: LocalDate, zone: TimeZone): Task = Task(
        id = 0,
        title = template.title,
        description = template.notes,
        dueDate = dueDateOf(template.due, today, zone),
        priority = template.priority,
        projectId = projectId,
        repeatAfter = template.repeatAfter,
        repeatMode = template.repeatMode,
        reminders = template.reminders.map { TaskReminder(relativePeriod = it.relativePeriod, relativeTo = it.relativeTo) },
    )

    /** Section 18.2: its own project while the person can still write there, else where a new task would go. */
    fun projectFor(template: TaskTemplate, writable: Set<Long>, fallback: Long): Long =
        template.projectId?.takeIf { it in writable } ?: fallback

    /** Section 18.3: a subtask kept by an edit, matched by title, keeps its fields and subtasks. */
    fun withSubtaskTitles(old: List<TaskTemplate>, titles: List<String>): List<TaskTemplate> {
        val left = old.toMutableList()
        return titles.map { title ->
            val kept = left.firstOrNull { it.title == title }
            if (kept != null) left.remove(kept)
            kept ?: TaskTemplate(title = title)
        }
    }
}
