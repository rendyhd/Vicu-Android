package com.rendyhd.vicu.util.pro

import com.rendyhd.vicu.util.DueDates
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class ImportType(val key: String, val fields: List<String>) {
    PROMISE("promise", listOf("owner", "what", "due", "initiative", "in_their_words", "done_when", "needs")),
    ACTION("action", listOf("owner", "what", "due", "for", "initiative")),
    UPDATE("update", listOf("id", "owner", "about", "what", "status", "new_due", "blocker")),
    OPEN_ITEM("open_item", listOf("what", "ask", "initiative")),
    DECISION("decision", listOf("what", "by", "initiative", "date")),
    DECISION_NEEDED("decision_needed", listOf("what", "from", "initiative")),
    VALUE("value", listOf("what", "where", "initiative")),
    ;

    companion object {
        fun of(key: String): ImportType? = entries.firstOrNull { it.key == key }
    }
}

/** The statuses an update may carry. */
val IMPORT_STATUSES: List<String> = listOf("delivered", "late", "not_yet", "renegotiated", "dropped")

data class ImportSource(val kind: String, val title: String, val date: LocalDate?)

/** One item of a `vicu-import/1` document, as read. A field that was not given or not read is null. */
data class ImportItem(
    /** 1-based position in the pasted items. */
    val index: Int,
    val type: ImportType,
    val what: String,
    val owner: String? = null,
    val due: LocalDate? = null,
    val initiative: String? = null,
    val inTheirWords: String? = null,
    val doneWhen: String? = null,
    val needs: List<String>? = null,
    /** The `for` field. */
    val forName: String? = null,
    val id: String? = null,
    val about: String? = null,
    val status: String? = null,
    val newDue: LocalDate? = null,
    val blocker: String? = null,
    val ask: String? = null,
    val by: String? = null,
    val from: String? = null,
    /** The `where` field. */
    val whereShown: String? = null,
    val date: LocalDate? = null,
) {
    /** The item in the import format, with only the fields that are set. */
    fun toJson(): JsonObject {
        val fields = LinkedHashMap<String, JsonElement>()
        fields["index"] = JsonPrimitive(index)
        fields["type"] = JsonPrimitive(type.key)
        fields["what"] = JsonPrimitive(what)
        fun put(key: String, value: String?) {
            if (value != null) fields[key] = JsonPrimitive(value)
        }
        put("owner", owner)
        put("due", due?.toString())
        put("initiative", initiative)
        put("in_their_words", inTheirWords)
        put("done_when", doneWhen)
        needs?.let { fields["needs"] = JsonArray(it.map(::JsonPrimitive)) }
        put("for", forName)
        put("id", id)
        put("about", about)
        put("status", status)
        put("new_due", newDue?.toString())
        put("blocker", blocker)
        put("ask", ask)
        put("by", by)
        put("from", from)
        put("where", whereShown)
        put("date", date?.toString())
        return JsonObject(fields)
    }

    companion object {
        /** An item from its JSON form (the one [toJson] writes). */
        fun fromJson(json: JsonObject): ImportItem = ImportItem(
            index = json.whole("index")?.toInt() ?: 0,
            type = ImportType.of(json.text("type").orEmpty()) ?: ImportType.VALUE,
            what = json.text("what").orEmpty(),
            owner = json.text("owner"),
            due = json.text("due")?.let { LocalDate.parse(it) },
            initiative = json.text("initiative"),
            inTheirWords = json.text("in_their_words"),
            doneWhen = json.text("done_when"),
            needs = json.list("needs")?.mapNotNull { it.asText() },
            forName = json.text("for"),
            id = json.text("id"),
            about = json.text("about"),
            status = json.text("status"),
            newDue = json.text("new_due")?.let { LocalDate.parse(it) },
            blocker = json.text("blocker"),
            ask = json.text("ask"),
            by = json.text("by"),
            from = json.text("from"),
            whereShown = json.text("where"),
            date = json.text("date")?.let { LocalDate.parse(it) },
        )
    }
}

class ReadImport(
    val ok: Boolean,
    val source: ImportSource,
    val items: List<ImportItem>,
    /** Plain sentences about what was not read; never fatal unless [ok] is false. */
    val problems: List<String>,
)

data class ImportInitiative(val id: Long, val title: String)

data class ImportOpenPromise(val taskId: Long, val person: Long, val title: String, val dir: String)

class ImportWorld(
    val people: List<Person>,
    /** The initiatives inside the area. */
    val initiatives: List<ImportInitiative>,
    val openPromises: List<ImportOpenPromise>,
)

enum class ImportAction(val key: String) {
    THEIR_PROMISE("their_promise"),
    CONFIRM("confirm"),
    MY_TASK("my_task"),
    VERDICT("verdict"),
    OPEN_ITEM("open_item"),
    DECISION("decision"),
    OWED("owed"),
    VALUE("value"),
}

data class ImportRow(
    val index: Int,
    val type: ImportType,
    val action: ImportAction,
    /** Who: the owner, `for`, `ask`, `by` or `from` depending on the type; `me` is no person. */
    val name: String?,
    val person: PersonMatch,
    val initiative: Long?,
    /** The initiative's name as written, when it matched none. */
    val initiativeName: String?,
    /** The promise an update is about. */
    val target: Long?,
    /** The target was found by wording, not by id: shown as "check". */
    val check: Boolean,
    /** The title of a task to make, if the row makes one. */
    val title: String?,
    val due: LocalDate?,
    /** Can be added as it is (otherwise the row starts unticked and says what is missing). */
    val ready: Boolean,
    val item: ImportItem,
)

class ImportMatch(
    val rows: List<ImportRow>,
    /** Open promises of theirs, by people in the import, that no update is about. */
    val notMentioned: List<Long>,
)

/** A promise as written to an export or a Copilot prompt. */
data class ExportPromise(
    val taskId: Long,
    val owner: String,
    val what: String,
    val due: LocalDate?,
    val initiative: String?,
)

/** The Copilot prompt template of `ai-tools-v1.json` (`pro.copilotPrompt`). */
class CopilotPromptTemplate(
    /** Lines; a list is an optional block, left out when one of its placeholders is empty. */
    val lines: List<List<String>>,
    /** Whether each entry of [lines] was a block (a list) in the template. */
    val blocks: List<Boolean>,
    /** The words for `{from_this}` per source kind. */
    val kinds: Map<String, String>,
) {
    companion object {
        fun fromJson(json: JsonObject): CopilotPromptTemplate {
            val lines = mutableListOf<List<String>>()
            val blocks = mutableListOf<Boolean>()
            for (entry in json.list("lines").orEmpty()) {
                if (entry is JsonArray) {
                    lines.add(entry.mapNotNull { it.asText() })
                    blocks.add(true)
                } else {
                    lines.add(listOf(entry.asText().orEmpty()))
                    blocks.add(false)
                }
            }
            val kinds = json.obj("kinds")?.mapNotNull { (key, value) -> value.asText()?.let { key to it } }?.toMap().orEmpty()
            return CopilotPromptTemplate(lines, blocks, kinds)
        }
    }
}

class CopilotPromptInput(
    val kind: String,
    val date: LocalDate,
    val me: String,
    val people: List<String>,
    val initiatives: List<String>,
    val open: List<ExportPromise>,
)

/**
 * Import and export (docs/cross-app-semantics-v1.md section 21.12), the same as the desktop's
 * `src/shared/pro/import.ts`: the `vicu-import/1` JSON a Copilot answer (or anyone) pastes in, read
 * forgivingly; matched against the people, initiatives and open promises; turned into a plan that
 * changes nothing until Add (decision 30). The export is the same format with ids for the round trip
 * (decision 33).
 */
object ProImport {
    const val FORMAT = "vicu-import/1"
    const val OVERLAP_MIN = 0.6

    private val WEEKDAYS = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
    private val FENCE = Regex("^[ \\t]*```[^\\n]*\$", RegexOption.MULTILINE)
    private val ISO_DATE = Regex("^(\\d{4})-(\\d{2})-(\\d{2})(?:[t ][\\d:.]+(?:z|[+-]\\d{2}:?\\d{2})?)?\$")
    private val IN_N = Regex("^in (\\d{1,3}) (day|days|week|weeks)\$")
    private val WEEKDAY = Regex("^(?:(?:on|this|next) )?([a-z]+)\$")
    private val SEPARATORS = Regex("[\\s-]+")
    private val ID_FORM = Regex("^(?:t-)?(\\d+)\$", RegexOption.IGNORE_CASE)
    private val ME_FORM = Regex("^(me|i|myself)\$", RegexOption.IGNORE_CASE)
    private val PLACEHOLDER = Regex("\\{([a-z_]+)\\}")
    private val lenient = Json { isLenient = false }

    private fun isWhitespace(ch: Char): Boolean = ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' || ch == '\u000B' || ch == '\u000C' || ch.isWhitespace()

    // --- Reading -----------------------------------------------------------------------------

    /** The text between the outermost braces, without code fences, and with trailing commas removed. */
    fun extractJson(text: String): String? {
        val unfenced = FENCE.replace(text, "")
        val start = unfenced.indexOf('{')
        val end = unfenced.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val body = unfenced.substring(start, end + 1)
        val out = StringBuilder()
        var inString = false
        var i = 0
        while (i < body.length) {
            val ch = body[i]
            if (inString) {
                out.append(ch)
                if (ch == '\\') {
                    i++
                    if (i < body.length) out.append(body[i])
                } else if (ch == '"') {
                    inString = false
                }
                i++
                continue
            }
            if (ch == '"') {
                inString = true
                out.append(ch)
                i++
                continue
            }
            if (ch == ',') {
                var j = i + 1
                while (j < body.length && isWhitespace(body[j])) j++
                if (j < body.length && (body[j] == '}' || body[j] == ']')) {
                    i++
                    continue
                }
            }
            out.append(ch)
            i++
        }
        return out.toString()
    }

    /**
     * A date as the import reads it, relative to [ref] (the source date): `YYYY-MM-DD` (a time after
     * it is ignored), `today`, `tomorrow`, a weekday (full or three letters, optionally after "on",
     * "this" or "next": the first such day after [ref]), `next week` (Monday of next week), `end of
     * week` (the first Friday on or after [ref]), `end of month`, `in N days`, `in N weeks`. Anything
     * else is not read (null).
     */
    fun readDate(text: String, ref: LocalDate): LocalDate? {
        val value = text.trim().lowercase()
        ISO_DATE.find(value)?.let { match ->
            val year = match.groupValues[1].toInt()
            val month = match.groupValues[2].toInt()
            val day = match.groupValues[3].toInt()
            return runCatching { LocalDate(year, month, day) }.getOrNull()
        }
        when (value) {
            "today" -> return ref
            "tomorrow" -> return ref.plus(1, DateTimeUnit.DAY)
            "next week" -> return DueDates.nextWeekStart(ref)
            "end of month" -> return DueDates.endOfMonth(ref)
            "end of week", "end of the week" -> return ref.plus((5 - ref.dayOfWeek.isoDayNumber + 7) % 7, DateTimeUnit.DAY)
        }
        IN_N.find(value)?.let { match ->
            val amount = match.groupValues[1].toInt() * (if (match.groupValues[2].startsWith("week")) 7 else 1)
            return ref.plus(amount, DateTimeUnit.DAY)
        }
        WEEKDAY.find(value)?.let { match ->
            val word = match.groupValues[1]
            val index = WEEKDAYS.indexOfFirst { it == word || it.take(3) == word }
            if (index >= 0) {
                val offset = ((index + 1 - ref.dayOfWeek.isoDayNumber + 7) % 7).let { if (it == 0) 7 else it }
                return ref.plus(offset, DateTimeUnit.DAY)
            }
        }
        return null
    }

    private fun JsonElement?.textOrNull(): String? = asText()?.trim()?.takeIf { it.isNotEmpty() }

    private fun readStatus(value: JsonElement?): String? {
        val raw = value.textOrNull() ?: return null
        val key = raw.lowercase().replace(SEPARATORS, "_")
        return when (key) {
            "done", "completed", "complete" -> "delivered"
            "notyet", "delayed", "missed" -> "not_yet"
            "cancelled", "canceled" -> "dropped"
            else -> key.takeIf { it in IMPORT_STATUSES }
        }
    }

    /**
     * Reads pasted text. Not JSON, or no items: `ok` false. A different or missing format, unknown
     * types and fields, dates that are not read and items without `what` are reported; the rest is
     * read.
     */
    fun readImport(input: String, today: LocalDate): ReadImport {
        val empty = ReadImport(false, ImportSource("meeting", "", null), emptyList(), emptyList())
        val json = extractJson(input)
        val parsed = json?.let { runCatching { lenient.parseToJsonElement(it) }.getOrNull() }
        val document = parsed as? JsonObject
            ?: return ReadImport(false, empty.source, emptyList(), listOf("The text is not a vicu-import/1 document."))
        val problems = mutableListOf<String>()
        val format = document["format"].textOrNull()
        if (format == null) problems.add("No format given; read as vicu-import/1.")
        else if (format != FORMAT) problems.add("Written as $format; read as vicu-import/1.")
        val rawSource = document["source"] as? JsonObject ?: JsonObject(emptyMap())
        val sourceDate = rawSource["date"].textOrNull()
        val date = sourceDate?.let { readDate(it, today) }
        if (sourceDate != null && date == null) problems.add("Source date \"$sourceDate\" not read; dates count from today.")
        val source = ImportSource(rawSource["kind"].textOrNull()?.lowercase() ?: "meeting", rawSource["title"].textOrNull() ?: "", date)
        val ref = date ?: today
        val rawItems = document["items"] as? JsonArray
        if (rawItems == null || rawItems.isEmpty()) {
            return ReadImport(false, source, emptyList(), problems + "No items found.")
        }
        val items = mutableListOf<ImportItem>()
        rawItems.forEachIndexed { position, raw ->
            val index = position + 1
            val fields = raw as? JsonObject
            if (fields == null) {
                problems.add("Item $index is not an object; left out.")
                return@forEachIndexed
            }
            val typeText = fields["type"].textOrNull()
            val typeKey = typeText?.lowercase()?.replace(SEPARATORS, "_") ?: ""
            val type = ImportType.of(typeKey)
            if (type == null) {
                problems.add("Item $index: unknown type \"${typeText ?: ""}\"; left out.")
                return@forEachIndexed
            }
            val known = type.fields
            val unknown = fields.keys.filter { it != "type" && it !in known }
            if (unknown.isNotEmpty()) problems.add("Item $index: ${unknown.joinToString(", ") { "\"$it\"" }} ignored.")
            fun text(key: String): String? = if (key in known) fields[key].textOrNull() else null
            fun day(key: String): LocalDate? {
                if (key !in known) return null
                val words = fields[key].textOrNull() ?: return null
                val read = readDate(words, ref)
                if (read == null) problems.add("Item $index: $key \"$words\" not read.")
                return read
            }
            val due = day("due")
            val newDue = day("new_due")
            val itemDate = day("date")
            val needs = if ("needs" in known) {
                (fields["needs"] as? JsonArray)?.map { need ->
                    val primitive = need as? JsonPrimitive
                    if (primitive != null && primitive !is JsonNull) primitive.content.trim() else ""
                }?.filter { it.isNotEmpty() }
            } else {
                null
            }
            var status: String? = null
            if (type == ImportType.UPDATE) {
                status = readStatus(fields["status"])
                if (status == null) problems.add("Item $index: status \"${fields["status"].textOrNull() ?: ""}\" not read.")
            }
            val what = fields["what"].textOrNull() ?: ""
            if (what.isEmpty() && type != ImportType.UPDATE) {
                problems.add("Item $index: no \"what\"; left out.")
                return@forEachIndexed
            }
            items.add(
                ImportItem(
                    index = index,
                    type = type,
                    what = what,
                    owner = text("owner"),
                    due = due,
                    initiative = text("initiative"),
                    inTheirWords = text("in_their_words"),
                    doneWhen = text("done_when"),
                    needs = needs,
                    forName = text("for"),
                    id = text("id"),
                    about = text("about"),
                    status = status,
                    newDue = newDue,
                    blocker = text("blocker"),
                    ask = text("ask"),
                    by = text("by"),
                    from = text("from"),
                    whereShown = text("where"),
                    date = itemDate,
                ),
            )
        }
        return ReadImport(items.isNotEmpty(), source, items, problems)
    }

    // --- Matching ----------------------------------------------------------------------------

    private val NO_PERSON = PersonMatch(null, PersonMatchKind.NONE)

    private fun isMe(name: String?): Boolean = !name.isNullOrBlank() && ME_FORM.matches(name.trim())

    /** Lower-case words of two or more letters or digits. */
    fun importTokens(value: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        for (ch in value.lowercase()) {
            if (ch.isLetterOrDigit()) {
                current.append(ch)
            } else {
                if (current.length >= 2) tokens.add(current.toString())
                current.clear()
            }
        }
        if (current.length >= 2) tokens.add(current.toString())
        return tokens
    }

    /** The share of [about]'s words that are in [title] (0 to 1). */
    fun tokenOverlap(about: String, title: String): Double {
        val wanted = importTokens(about).distinct()
        if (wanted.isEmpty()) return 0.0
        val have = importTokens(title).toSet()
        return wanted.count { it in have }.toDouble() / wanted.size
    }

    /** Matches every item: people, initiatives, update targets; and the open promises not mentioned. */
    fun matchImport(read: ReadImport, world: ImportWorld): ImportMatch {
        fun initiative(name: String?): Pair<Long?, String?> {
            if (name.isNullOrEmpty()) return null to null
            val wanted = name.trim().lowercase()
            val found = world.initiatives.filter { it.title.trim().lowercase() == wanted }
            return if (found.size == 1) found[0].id to null else null to name.trim()
        }

        fun person(name: String?): PersonMatch =
            if (!name.isNullOrEmpty() && !isMe(name)) People.matchPerson(name, world.people) else NO_PERSON

        val targeted = mutableSetOf<Long>()
        val people = mutableSetOf<Long>()
        val rows = read.items.map { item ->
            val (initiativeId, initiativeName) = initiative(item.initiative)
            fun row(
                action: ImportAction,
                name: String?,
                person: PersonMatch,
                title: String?,
                due: LocalDate?,
                ready: Boolean,
                target: Long? = null,
                check: Boolean = false,
            ) = ImportRow(item.index, item.type, action, name, person, initiativeId, initiativeName, target, check, title, due, ready, item)

            when (item.type) {
                ImportType.PROMISE -> {
                    val match = person(item.owner)
                    match.id?.let(people::add)
                    val clearYes = !item.inTheirWords.isNullOrEmpty()
                    val name = item.owner
                    row(
                        if (clearYes) ImportAction.THEIR_PROMISE else ImportAction.CONFIRM,
                        name,
                        match,
                        if (clearYes) item.what else Promises.confirmTitle(name ?: "?", item.what),
                        item.due,
                        match.id != null,
                    )
                }
                ImportType.ACTION -> {
                    val match = person(item.forName)
                    row(ImportAction.MY_TASK, item.forName, match, item.what, item.due, item.forName.isNullOrEmpty() || match.id != null)
                }
                ImportType.UPDATE -> {
                    val match = person(item.owner)
                    match.id?.let(people::add)
                    var target: Long? = null
                    var check = false
                    val id = item.id?.let { ID_FORM.find(it.trim()) }
                    if (id != null && world.openPromises.any { it.taskId == id.groupValues[1].toLong() }) {
                        target = id.groupValues[1].toLong()
                    } else {
                        val about = item.about ?: item.what
                        var best = 0.0
                        for (promise in world.openPromises) {
                            if (match.id == null || promise.person != match.id) continue
                            val score = tokenOverlap(about, promise.title)
                            if (score >= OVERLAP_MIN && (score > best || (score == best && target != null && promise.taskId < target))) {
                                best = score
                                target = promise.taskId
                            }
                        }
                        check = target != null
                    }
                    val resolved = target
                    if (resolved != null) {
                        targeted.add(resolved)
                        world.openPromises.firstOrNull { it.taskId == resolved }?.person?.let(people::add)
                    }
                    row(ImportAction.VERDICT, item.owner, match, null, item.newDue, resolved != null && item.status != null, resolved, check)
                }
                ImportType.OPEN_ITEM -> {
                    val match = person(item.ask)
                    row(
                        ImportAction.OPEN_ITEM,
                        item.ask,
                        match,
                        if (item.ask != null) "Ask ${item.ask}: ${item.what}" else null,
                        null,
                        item.ask.isNullOrEmpty() || match.id != null,
                    )
                }
                ImportType.DECISION -> row(ImportAction.DECISION, item.by, person(item.by), null, null, true)
                ImportType.DECISION_NEEDED -> row(ImportAction.OWED, item.from, person(item.from), null, null, true)
                ImportType.VALUE -> row(ImportAction.VALUE, null, NO_PERSON, null, null, true)
            }
        }
        val notMentioned = world.openPromises
            .filter { it.dir == "theirs" && it.person in people && it.taskId !in targeted }
            .map { it.taskId }
            .sorted()
        return ImportMatch(rows, notMentioned)
    }

    // --- Export and the Copilot prompt -------------------------------------------------------

    @OptIn(ExperimentalSerializationApi::class)
    private val pretty = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /** Decision 33: the open promises as `vicu-import/1` with ids, for Copilot to update. */
    fun exportOpen(title: String, date: LocalDate, promises: List<ExportPromise>): String {
        val items = promises.sortedBy { it.taskId }.map { promise ->
            val item = LinkedHashMap<String, JsonElement>()
            item["type"] = JsonPrimitive("promise")
            item["id"] = JsonPrimitive("t-${promise.taskId}")
            item["owner"] = JsonPrimitive(promise.owner)
            item["what"] = JsonPrimitive(promise.what)
            promise.due?.let { item["due"] = JsonPrimitive(it.toString()) }
            promise.initiative?.takeIf { it.isNotEmpty() }?.let { item["initiative"] = JsonPrimitive(it) }
            JsonObject(item)
        }
        val document = JsonObject(
            mapOf(
                "format" to JsonPrimitive(FORMAT),
                "source" to JsonObject(
                    mapOf("kind" to JsonPrimitive("export"), "title" to JsonPrimitive(title), "date" to JsonPrimitive(date.toString())),
                ),
                "items" to JsonArray(items),
            ),
        )
        return pretty.encodeToString(JsonElement.serializer(), document)
    }

    /**
     * The prompt for Copilot (the fixture's `pro.copilotPrompt` in ai-tools-v1.json): placeholders
     * `{from_this}`, `{kind}`, `{me}`, `{people}`, `{initiatives}`, `{date}`, `{open}` (the open
     * promises as one line of JSON with their ids).
     */
    fun copilotPrompt(template: CopilotPromptTemplate, input: CopilotPromptInput): String {
        val open = if (input.open.isNotEmpty()) {
            JsonArray(
                input.open.sortedBy { it.taskId }.map { promise ->
                    val item = LinkedHashMap<String, JsonElement>()
                    item["id"] = JsonPrimitive("t-${promise.taskId}")
                    item["owner"] = JsonPrimitive(promise.owner)
                    item["what"] = JsonPrimitive(promise.what)
                    promise.due?.let { item["due"] = JsonPrimitive(it.toString()) }
                    JsonObject(item)
                },
            ).toString()
        } else {
            ""
        }
        val knownKind = template.kinds[input.kind] != null
        val values = mapOf(
            "from_this" to (template.kinds[input.kind] ?: template.kinds["meeting"].orEmpty()),
            "kind" to (if (knownKind) input.kind else "meeting"),
            "me" to input.me,
            "people" to input.people.joinToString(", "),
            "initiatives" to input.initiatives.joinToString(", "),
            "date" to input.date.toString(),
            "open" to open,
        )
        fun fill(line: String): String = PLACEHOLDER.replace(line) { match -> values[match.groupValues[1]].orEmpty() }
        val lines = mutableListOf<String>()
        template.lines.forEachIndexed { index, entry ->
            if (!template.blocks[index]) {
                lines.add(fill(entry[0]))
                return@forEachIndexed
            }
            val empty = entry.any { line -> PLACEHOLDER.findAll(line).any { values[it.groupValues[1]].isNullOrEmpty() } }
            if (!empty) lines.addAll(entry.map(::fill))
        }
        return lines.joinToString("\n")
    }
}
