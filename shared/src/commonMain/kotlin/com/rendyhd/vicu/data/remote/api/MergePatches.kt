package com.rendyhd.vicu.data.remote.api

import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskReminder
import com.rendyhd.vicu.util.DateUtils
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object MergePatches {
    val taskReadOnlyFields = setOf(
        "\$schema",
        "id",
        "created",
        "updated",
        "created_by",
        "done_at",
        "identifier",
        "index",
        "labels",
        "attachments",
        "related_tasks",
        "position",
        "kanban_position",
        "max_permission",
    )

    fun task(previous: Task?, current: Task): JsonObject = buildJsonObject {
        putChanged("title", previous?.title, current.title)
        putChanged("description", previous?.description, current.description)
        putChanged("done", previous?.done, current.done)
        putDateChanged("due_date", previous?.dueDate, current.dueDate)
        putChanged("priority", previous?.priority, current.priority)
        putChanged("project_id", previous?.projectId, current.projectId)
        if (previous == null ||
            previous.repeatAfter != current.repeatAfter ||
            previous.repeatMode != current.repeatMode
        ) {
            // Recurrence is a two-field value. Always patch both halves together, including
            // explicit zeroes when clearing, so queued/offline updates cannot create hybrids.
            put("repeat_after", current.repeatAfter)
            put("repeat_mode", current.repeatMode)
        }
        putDateChanged("start_date", previous?.startDate, current.startDate)
        putDateChanged("end_date", previous?.endDate, current.endDate)
        putChanged("hex_color", previous?.hexColor?.removePrefix("#"), current.hexColor.removePrefix("#"))
        putChanged("percent_done", previous?.percentDone, current.percentDone)
        putChanged("bucket_id", previous?.bucketId, current.bucketId)
        putChanged("is_favorite", previous?.isFavorite, current.isFavorite)
        if (previous == null || previous.reminders != current.reminders) {
            put(
                "reminders",
                JsonArray(
                    current.reminders.map { reminder(it) },
                ),
            )
        }
    }

    /**
     * API v2 validates `reminder` as an RFC 3339 date-time and rejects the whole request on
     * `""` (issue #30). Relative reminders therefore omit it, and absolute ones omit the
     * blank `relative_to`.
     */
    fun reminder(reminder: TaskReminder): JsonObject = buildJsonObject {
        if (reminder.reminder.isNotBlank() && !DateUtils.isNullDate(reminder.reminder)) {
            put("reminder", reminder.reminder)
        }
        put("relative_period", reminder.relativePeriod)
        if (reminder.relativeTo.isNotBlank()) put("relative_to", reminder.relativeTo)
    }

    /** Drops blank reminder fields from task patches queued by older versions (issue #30). */
    fun sanitizeTaskPatch(patch: JsonObject): JsonObject {
        val reminders = patch["reminders"] as? JsonArray ?: return patch
        val cleaned = reminders.map { item ->
            val obj = item as? JsonObject ?: return@map item
            JsonObject(
                obj.filterNot { (key, value) ->
                    (key == "reminder" || key == "relative_to") && isBlankReminderValue(value)
                },
            )
        }
        return JsonObject(patch + ("reminders" to JsonArray(cleaned)))
    }

    private fun isBlankReminderValue(value: JsonElement): Boolean =
        value is JsonNull ||
            (value is JsonPrimitive && value.isString &&
                (value.content.isBlank() || DateUtils.isNullDate(value.content)))

    fun taskDone(done: Boolean): JsonObject = buildJsonObject {
        put("done", done)
    }

    fun project(previous: Project?, current: Project): JsonObject = buildJsonObject {
        putChanged("title", previous?.title, current.title)
        putChanged("description", previous?.description, current.description)
        putChanged("hex_color", previous?.hexColor?.removePrefix("#"), current.hexColor.removePrefix("#"))
        putChanged("parent_project_id", previous?.parentProjectId, current.parentProjectId)
        putChanged("position", previous?.position, current.position)
        putChanged("is_archived", previous?.isArchived, current.isArchived)
        putChanged("is_favorite", previous?.isFavorite, current.isFavorite)
        putChanged("identifier", previous?.identifier, current.identifier)
    }

    fun label(previous: Label?, current: Label): JsonObject = buildJsonObject {
        putChanged("title", previous?.title, current.title)
        putChanged("description", previous?.description, current.description)
        putChanged("hex_color", previous?.hexColor?.removePrefix("#"), current.hexColor.removePrefix("#"))
    }

    fun merge(first: JsonObject, second: JsonObject): JsonObject =
        JsonObject(first + second)

    private fun kotlinx.serialization.json.JsonObjectBuilder.putChanged(
        name: String,
        previous: String?,
        current: String,
    ) {
        if (previous == null || previous != current) put(name, current)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putChanged(
        name: String,
        previous: Boolean?,
        current: Boolean,
    ) {
        if (previous == null || previous != current) put(name, current)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putChanged(
        name: String,
        previous: Int?,
        current: Int,
    ) {
        if (previous == null || previous != current) put(name, current)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putChanged(
        name: String,
        previous: Long?,
        current: Long,
    ) {
        if (previous == null || previous != current) put(name, current)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putChanged(
        name: String,
        previous: Double?,
        current: Double,
    ) {
        if (previous == null || previous != current) put(name, current)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putDateChanged(
        name: String,
        previous: String?,
        current: String,
    ) {
        if (previous == null || previous != current) {
            val value: JsonElement =
                if (current.isBlank() || DateUtils.isNullDate(current)) JsonNull else JsonPrimitive(current)
            put(name, value)
        }
    }
}
