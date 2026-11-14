package com.rendyhd.vicu.ai.dictation

import com.rendyhd.vicu.ai.AiContract
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The text of a dictation request (`docs/ai-assistant-v1.md`, sections 4.1 and 4.4), the same on
 * every device for the same state: the system prompt, and the user message with the `<context>`
 * JSON, the drafts when editing, and the transcript.
 */
object DictationRequests {
    fun system(editing: Boolean): String =
        if (editing) AiContract.prompt("dictation") + "\n\n" + AiContract.prompt("dictationEdit") else AiContract.prompt("dictation")

    /** A brain dump's system prompt (sections 4.8.1 and 4.8.4). */
    fun brainDumpSystem(editing: Boolean): String {
        val organize = AiContract.prompt("dictation") + "\n\n" + AiContract.prompt("brainDump")
        return if (editing) organize + "\n\n" + AiContract.prompt("brainDumpEdit") else organize
    }

    /** The context JSON, keys in the contract's order, written without extra spaces. */
    fun contextJson(context: DictationContext): String = buildJsonObject {
        put("now", minute(context.now))
        put("weekday", weekday(context.now))
        put("time_zone", context.zone.id)
        put("week_starts", context.weekStart.key)
        put("list", context.list)
        put("default_project_id", context.defaultProjectId)
        putJsonArray("projects") {
            for (p in context.projects) {
                add(
                    buildJsonObject {
                        put("id", p.id)
                        put("title", p.title)
                        put("path", p.path)
                    },
                )
            }
        }
        putJsonArray("labels") { context.labels.forEach { add(JsonPrimitive(it.title)) } }
        putJsonArray("places") { context.places.forEach { add(JsonPrimitive(it.name)) } }
    }.toString()

    fun userMessage(context: DictationContext, transcript: String, drafts: List<CheckedDraft>? = null): String = buildString {
        append("<context>\n").append(contextJson(context)).append("\n</context>\n")
        if (drafts != null) append("<drafts>\n").append(draftsJson(drafts, context)).append("\n</drafts>\n")
        append("<transcript>\n").append(cleanTranscript(transcript)).append("\n</transcript>")
    }

    /**
     * A brain dump's edit (section 4.8.4): the context, the plan so far in the answer's own shape,
     * and what the person said or typed next. Without [plan] it is the dump to organize (4.8.1).
     */
    fun brainDumpMessage(context: DictationContext, transcript: String, plan: CheckResult? = null): String = buildString {
        append("<context>\n").append(contextJson(context)).append("\n</context>\n")
        if (plan != null) append("<plan>\n").append(planJson(plan.projects, plan.drafts, context)).append("\n</plan>\n")
        append("<transcript>\n").append(cleanTranscript(transcript)).append("\n</transcript>")
    }

    /** The plan in the brain dump answer's shape: the projects, then the tasks with `project_ref`. */
    fun planJson(projects: List<CheckedProject>, drafts: List<CheckedDraft>, context: DictationContext): String = buildJsonObject {
        putJsonArray("projects") {
            for (p in projects) {
                add(
                    buildJsonObject {
                        put("ref", p.ref)
                        put("title", p.title)
                        put("parent_id", p.parentId?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("parent_ref", p.parentRef?.let { JsonPrimitive(it) } ?: JsonNull)
                    },
                )
            }
        }
        putJsonArray("tasks") { drafts.forEach { add(draftJson(it, context, withProjectRef = true)) } }
    }.toString()

    /** The drafts in the answer's own shape (section 4.4): labels as names, the due date as `when` with `said` null. */
    fun draftsJson(drafts: List<CheckedDraft>, context: DictationContext): String = buildJsonObject {
        putJsonArray("tasks") { drafts.forEach { add(draftJson(it, context, withProjectRef = false)) } }
    }.toString()

    private fun draftJson(d: CheckedDraft, context: DictationContext, withProjectRef: Boolean): JsonObject =
        buildJsonObject {
            put("ref", d.ref)
            put("title", d.title)
            put("notes", d.notes)
            putJsonObject("when") {
                put("said", JsonNull)
                put("date", d.due?.date?.toString()?.let { JsonPrimitive(it) } ?: JsonNull)
                put("time", if (d.due != null && d.dueHasTime) JsonPrimitive(hhmm(d.due)) else JsonNull)
            }
            if (withProjectRef) {
                put("project_id", if (d.projectRef != null) JsonNull else JsonPrimitive(d.projectId))
                put("project_ref", d.projectRef?.let { JsonPrimitive(it) } ?: JsonNull)
            } else {
                put("project_id", d.projectId)
            }
            putJsonArray("labels") { d.labels.forEach { add(JsonPrimitive(it.title)) } }
            put("priority", d.priority?.let { JsonPrimitive(it) } ?: JsonNull)
            put(
                "repeat",
                d.repeat?.let { buildJsonObject { put("every", it.every); put("unit", it.unit) } } ?: JsonNull,
            )
            put(
                "reminder",
                d.reminder?.let { r ->
                    buildJsonObject {
                        put("before_due_minutes", r.beforeDueMinutes?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("at", r.at?.let { JsonPrimitive(minute(it)) } ?: JsonNull)
                    }
                } ?: JsonNull,
            )
            put(
                "place",
                d.place?.let { p ->
                    val name = context.places.firstOrNull { it.id == p.placeId }?.name
                    if (name == null) JsonNull else buildJsonObject { put("name", name); put("trigger", p.trigger) }
                } ?: JsonNull,
            )
            putJsonArray("subtasks") { d.subtasks.forEach { add(JsonPrimitive(it)) } }
            put("unsure", buildJsonArray { })
        }

    /** `</transcript>` inside is written `</ transcript>`; at most 20,000 characters, cut at the last space. */
    fun cleanTranscript(transcript: String): String {
        var text = transcript.replace("</transcript>", "</ transcript>")
        val max = AiContract.limits.transcriptMaxChars
        if (text.length > max) {
            val cut = text.lastIndexOf(' ', max)
            text = if (cut > 0) text.substring(0, cut) else text.substring(0, max)
        }
        return text
    }

    fun minute(t: LocalDateTime): String = "${t.date}T${hhmm(t)}"

    private fun hhmm(t: LocalDateTime) = "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"

    private fun weekday(t: LocalDateTime): String = t.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }

    /** The answer schema as a [JsonObject]. */
    val answerSchema: JsonObject get() = AiContract.dictationAnswerSchema

    val brainDumpSchema: JsonObject get() = AiContract.brainDumpAnswerSchema
}
