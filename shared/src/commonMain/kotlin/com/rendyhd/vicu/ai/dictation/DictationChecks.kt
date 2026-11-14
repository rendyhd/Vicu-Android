package com.rendyhd.vicu.ai.dictation

import com.rendyhd.vicu.ai.AiContract
import com.rendyhd.vicu.util.parser.ParserConfig
import com.rendyhd.vicu.util.parser.TaskParser
import com.rendyhd.vicu.util.parser.TokenType
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The checks every draft goes through (`docs/ai-assistant-v1.md`, section 4.3), in order. The
 * model's answer is a proposal: dates come from the quick-add parser when it can read the words,
 * every other field is checked against the context, and what fails is left empty and reported.
 * Vectors: `dictation.checks` of `ai-tools-v1.json`.
 */
object DictationChecks {
    private val limits get() = AiContract.limits

    /** The quick-add parser with only dates read (section 4.3, step 4). */
    fun datesOnly(locale: String?): ParserConfig = ParserConfig(
        enabled = true,
        suppressTypes = TokenType.entries.toSet() - TokenType.DATE,
        bangToday = false,
        locale = locale,
    )

    /**
     * [answer] matches `dictation.answerSchema` (the caller checked it). A brain dump (section 4.8.3)
     * allows [draftsMax] drafts and reads each task's `project_ref` through [resolveProjectRef].
     */
    fun check(
        answer: JsonObject,
        context: DictationContext,
        draftsMax: Int = limits.draftsMax,
        resolveProjectRef: ((String) -> ProjectTarget?)? = null,
    ): CheckResult {
        val fields = AiContract.dictationFields
        val dropped = mutableListOf<DroppedField>()
        val all = answer["tasks"]?.jsonArray.orEmpty().map { it.jsonObject }

        // 1. Drafts: at most 25; refs renumbered when one is empty or repeated.
        for (extra in all.drop(draftsMax)) {
            dropped += DroppedField(extra.str("ref"), "task", extra["title"] ?: JsonNull)
        }
        val tasks = all.take(draftsMax)
        val refs = tasks.map { it.str("ref").orEmpty() }
        val renumber = refs.any { it.isEmpty() } || refs.toSet().size != refs.size

        val projectIds = context.projects.mapTo(HashSet()) { it.id }
        val drafts = mutableListOf<CheckedDraft>()
        tasks.forEachIndexed { index, task ->
            val ref = if (renumber) "d${index + 1}" else refs[index]
            val guessed = task["unsure"]?.jsonArray.orEmpty().mapNotNullTo(HashSet()) { it.jsonPrimitive.contentOrNull }
                .filterTo(HashSet()) { it in fields }

            // 2. Title.
            val rawTitle = task.str("title").orEmpty()
            var title = oneLine(rawTitle).trim()
            var notes = task.str("notes").orEmpty().trim()
            if (title.isEmpty()) {
                dropped += DroppedField(ref, "title", JsonPrimitive(rawTitle))
                return@forEachIndexed
            }
            if (title.length > limits.titleMaxChars) {
                val cut = title.lastIndexOf(' ', limits.titleMaxChars)
                val rest: String
                if (cut < 0) {
                    rest = title.substring(limits.titleMaxChars)
                    title = title.substring(0, limits.titleMaxChars)
                } else {
                    rest = title.substring(cut + 1)
                    title = title.substring(0, cut)
                }
                notes = if (notes.isEmpty()) rest else "$rest\n\n$notes"
            }
            // 3. Notes.
            notes = notes.takeUtf16(limits.notesMaxChars)

            // 4. When.
            val reading = readWhen(task["when"] as? JsonObject, context)
            var due = reading.due
            var dueHasTime = reading.hasTime
            if (reading.guessed) guessed += "when"
            reading.droppedValue?.let { dropped += DroppedField(ref, "when", JsonPrimitive(it)) }
            if (due == null && context.defaultDue != null) {
                due = context.defaultDue.atTime(DATE_ONLY)
                dueHasTime = false
            }

            // 5. Project, after a brain dump's new project (section 4.8.3, step 5).
            val givenRef = if (resolveProjectRef != null) task.str("project_ref")?.takeIf { it.isNotEmpty() } else null
            val target = givenRef?.let { resolveProjectRef?.invoke(it) }
            if (givenRef != null && target == null) guessed += "project"
            val modelProject = (task["project_id"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull
            val projectId = when {
                target is ProjectTarget.New -> context.defaultProjectId
                target is ProjectTarget.Existing -> target.id
                modelProject == null -> context.defaultProjectId
                modelProject in projectIds -> modelProject
                else -> {
                    guessed += "project"
                    context.defaultProjectId
                }
            }

            // 6. Labels.
            val labels = mutableListOf<DraftLabel>()
            val seen = HashSet<String>()
            for (element in task["labels"]?.jsonArray.orEmpty()) {
                val name = stripPrefix(element.jsonPrimitive.contentOrNull.orEmpty())
                if (name.isEmpty() || !seen.add(name.lowercase())) continue
                val existing = context.labels.firstOrNull { it.title.lowercase() == name.lowercase() }
                labels += if (existing != null) DraftLabel(existing.id, existing.title) else DraftLabel(null, name)
            }

            // 7. Priority.
            var priority: Int? = null
            val priorityElement = task["priority"]
            if (priorityElement != null && priorityElement !is JsonNull) {
                val value = wholeNumber(priorityElement)
                if (value != null && value in 1..4) priority = value.toInt() else dropped += DroppedField(ref, "priority", priorityElement)
            }

            // 8. Repeat.
            var repeat: DraftRepeat? = null
            val repeatElement = task["repeat"]
            if (repeatElement is JsonObject) {
                val every = repeatElement["every"]?.let { wholeNumber(it) }
                val unit = repeatElement.str("unit")
                if (every != null && every in 1..limits.repeatEveryMax.toLong() && unit in REPEAT_UNITS) {
                    repeat = DraftRepeat(every.toInt(), unit!!)
                } else {
                    dropped += DroppedField(ref, "repeat", repeatElement)
                }
            }

            // 9. Reminder.
            var reminder: DraftReminder? = null
            val reminderElement = task["reminder"]
            if (reminderElement is JsonObject) {
                val before = reminderElement["before_due_minutes"]?.takeIf { it !is JsonNull }
                val at = reminderElement["at"]?.takeIf { it !is JsonNull }
                if (before != null && at == null) {
                    val minutes = wholeNumber(before)
                    if (minutes != null && minutes in 0..limits.reminderBeforeDueMaxMinutes.toLong() && due != null) {
                        reminder = DraftReminder(beforeDueMinutes = minutes.toInt())
                    }
                } else if (at != null && before == null) {
                    val moment = (at as? JsonPrimitive)?.contentOrNull?.let { validMoment(it) }
                    if (moment != null && moment > context.now) reminder = DraftReminder(at = moment)
                }
                if (reminder == null) dropped += DroppedField(ref, "reminder", reminderElement)
            }

            // 10. Place.
            var place: DraftPlace? = null
            val placeElement = task["place"]
            if (placeElement is JsonObject) {
                val key = placeKey(placeElement.str("name").orEmpty().trim())
                val trigger = placeElement.str("trigger")
                val match = context.places.filter { placeKey(it.name) == key }.minByOrNull { it.id }
                if (match != null && (trigger == "arrive" || trigger == "leave")) {
                    place = DraftPlace(match.id, trigger)
                } else {
                    dropped += DroppedField(ref, "place", placeElement)
                }
            }

            // 11. Subtasks.
            val subtasks = task["subtasks"]?.jsonArray.orEmpty()
                .map { oneLine(it.jsonPrimitive.contentOrNull.orEmpty()).trim().takeUtf16(limits.subtaskTitleMaxChars) }
                .filter { it.isNotEmpty() }
                .take(limits.subtasksMax)

            drafts += CheckedDraft(
                ref = ref,
                title = title,
                notes = notes,
                due = due,
                dueHasTime = dueHasTime,
                projectId = projectId,
                projectRef = (target as? ProjectTarget.New)?.ref,
                labels = labels.take(limits.labelsMax),
                priority = priority,
                repeat = repeat,
                reminder = reminder,
                place = place,
                subtasks = subtasks,
                guessed = fields.filter { it in guessed },
            )
        }
        val unclear = answer["unclear"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
        return CheckResult(drafts, dropped, unclear)
    }

    /** The due date a `when` object gives (section 4.3, step 4, without the list's default). */
    data class WhenReading(val due: LocalDateTime?, val hasTime: Boolean, val guessed: Boolean, val droppedValue: String?)

    fun readWhen(whenObject: JsonObject?, context: DictationContext): WhenReading {
        val said = whenObject?.str("said")
        val parsed = said?.let { readSaid(it, context) }
        if (parsed != null) return WhenReading(parsed.first, parsed.second, guessed = false, droppedValue = null)
        val dateText = whenObject?.str("date")
        val timeText = whenObject?.str("time")
        if (dateText == null && timeText == null) {
            return WhenReading(null, false, false, said)
        }
        val date = dateText?.let { validDate(it) }
        val time = timeText?.let { validTime(it) }
        var ok = (dateText == null || date != null) && (timeText == null || time != null)
        val today = context.now.date
        val limit = today.plus(limits.modelDateMaxYears, DateTimeUnit.YEAR)
        if (ok && date != null && (date < today || date > limit)) ok = false
        if (!ok) return WhenReading(null, false, false, said ?: dateText)
        return when {
            date == null -> {
                var moment = today.atTime(time!!)
                if (moment <= context.now) moment = today.plus(1, DateTimeUnit.DAY).atTime(time)
                WhenReading(moment, true, guessed = true, droppedValue = null)
            }
            time == null -> WhenReading(date.atTime(DATE_ONLY), false, guessed = true, droppedValue = null)
            else -> WhenReading(date.atTime(time), true, guessed = true, droppedValue = null)
        }
    }

    /** The parser's reading of the model's `said` words, with the dictation's reference time. */
    fun readSaid(said: String, context: DictationContext): Pair<LocalDateTime, Boolean>? {
        val result = TaskParser.parse(
            said, datesOnly(context.locale), reference = context.now, zone = context.zone, weekStart = context.weekStart,
        )
        val date = result.dueDate ?: return null
        return if (result.dueDateHasTime) {
            LocalDateTime(date.date, LocalTime(date.hour, date.minute)) to true
        } else {
            date.date.atTime(DATE_ONLY) to false
        }
    }

    /** A date-only due date's local time (semantics 1.1). */
    val DATE_ONLY = LocalTime(23, 59, 59)

    private val REPEAT_UNITS = setOf("day", "week", "month", "year")

    private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")
    private val TIME = Regex("""\d{2}:\d{2}""")
    private val MOMENT = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}""")

    fun validDate(text: String): LocalDate? {
        if (!DATE.matches(text)) return null
        return runCatching { LocalDate(text.substring(0, 4).toInt(), text.substring(5, 7).toInt(), text.substring(8, 10).toInt()) }.getOrNull()
    }

    fun validTime(text: String): LocalTime? {
        if (!TIME.matches(text)) return null
        val hour = text.substring(0, 2).toInt()
        val minute = text.substring(3, 5).toInt()
        return if (hour < 24 && minute < 60) LocalTime(hour, minute) else null
    }

    fun validMoment(text: String): LocalDateTime? {
        if (!MOMENT.matches(text)) return null
        val date = validDate(text.substring(0, 10)) ?: return null
        val time = validTime(text.substring(11, 16)) ?: return null
        return date.atTime(time)
    }

    private val LINE_BREAKS = Regex("[\r\n]+")

    fun oneLine(text: String): String = text.replace(LINE_BREAKS, " ")

    /** One leading `#`, `@`, `*` or `+` removed (section 4.3, step 6). */
    fun stripPrefix(name: String): String {
        val trimmed = name.trim()
        return if (trimmed.isNotEmpty() && trimmed[0] in "#@*+") trimmed.substring(1).trim() else trimmed
    }

    /** Semantics 5.3: ignoring case and curly apostrophes. */
    fun placeKey(name: String): String = name.replace('’', '\'').replace('‘', '\'').lowercase()

    private fun wholeNumber(element: JsonElement): Long? {
        val p = element as? JsonPrimitive ?: return null
        if (p.isString || p is JsonNull) return null
        p.longOrNull?.let { return it }
        val d = p.contentOrNull?.toDoubleOrNull() ?: return null
        return if (d == kotlin.math.floor(d) && !d.isInfinite()) d.toLong() else null
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    @Suppress("unused")
    private fun JsonElement.intValue(): Int? = (this as? JsonPrimitive)?.intOrNull
}

/** At most [max] UTF-16 units, never splitting a surrogate pair. */
internal fun String.takeUtf16(max: Int): String {
    if (length <= max) return this
    var end = max
    if (end > 0 && this[end - 1].isHighSurrogate()) end--
    return substring(0, end)
}
