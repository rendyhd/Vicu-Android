package com.rendyhd.vicu.util.pro

import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.CrossAppFixture
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The `pro` and `customListPro` vectors of test-fixtures/cross-app-semantics-v1.json (contract
 * sections 21 and 3.8) and the Copilot prompt of ai-tools-v1.json, read the way the desktop's
 * `pro-suite.ts` reads them: local wall-clock times are converted in the zone under test (a value
 * ending in Z is UTC), ledger values get revisions, and each task is built from its vector fields.
 */
internal object ProFixture {
    val root: JsonObject = Json.parseToJsonElement(CrossAppFixture.readTestFixture("cross-app-semantics-v1.json")).jsonObject
    val pro: JsonObject = root.getValue("pro").jsonObject
    val customListPro: JsonObject = root.getValue("customListPro").jsonObject
    val copilotTemplate: JsonObject = Json.parseToJsonElement(CrossAppFixture.readTestFixture("ai-tools-v1.json"))
        .jsonObject.getValue("pro").jsonObject.getValue("copilotPrompt").jsonObject

    /** The zones every vector runs in (the contract's three). */
    val zones: List<TimeZone> = listOf("Pacific/Auckland", "America/New_York", "Europe/Amsterdam").map(TimeZone::of)

    const val NULL_DATE = "0001-01-01T00:00:00Z"

    fun forEachZone(block: (TimeZone) -> Unit) {
        for (zone in zones) {
            try {
                block(zone)
            } catch (error: Throwable) {
                throw AssertionError("in ${zone.id}: ${error.message}", error)
            }
        }
    }

    fun objects(element: JsonElement?): List<JsonObject> = element!!.jsonArray.map { it.jsonObject }
    fun JsonObject.objects(key: String): List<JsonObject> = getValue(key).jsonArray.map { it.jsonObject }
    fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.boolean ?: false
    fun JsonObject.intOf(key: String): Int = getValue(key).jsonPrimitive.int

    fun day(value: String): LocalDate = LocalDate.parse(value)

    /** `2026-10-06T10:00:00` as an instant in [zone]; a value ending in Z (or with an offset) as written. */
    fun local(value: String, zone: TimeZone): Instant =
        if (Regex("Z$|[+-]\\d{2}:\\d{2}$").containsMatchIn(value)) Instant.parse(value) else LocalDateTime.parse(value).toInstant(zone)

    fun iso(value: String?, zone: TimeZone): String? = value?.let { local(it, zone).toString() }

    fun today(now: Instant, zone: TimeZone): LocalDate = now.toLocalDateTime(zone).date

    /** A ledger from vector values: every record gets a revision. */
    fun ledgerOf(values: JsonObject?): ProLedger {
        val ledger = LinkedHashMap<String, JsonElement>(LedgerRules.empty().json)
        var wall = 1L
        fun revision(): JsonObject = JsonObject(
            mapOf("wall_time_ms" to JsonPrimitive(wall++), "counter" to JsonPrimitive(0), "device_id" to JsonPrimitive("v")),
        )
        for ((collection, records) in values.orEmpty()) {
            when (collection) {
                "estimates" -> ledger["estimates"] = records
                "settings" -> ledger["settings"] = JsonObject(mapOf("value" to records, "revision" to revision()))
                else -> ledger[collection] = JsonObject(
                    records.jsonObject.mapValues { (_, value) -> JsonObject(mapOf("value" to value, "revision" to revision())) },
                )
            }
        }
        return ProLedger(JsonObject(ledger))
    }

    private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())

    /** A ledger from the compact merge form `{ v, r: [wall, counter, device] }`. */
    fun compactLedger(document: JsonObject): ProLedger {
        fun expand(record: JsonElement): JsonObject {
            val fields = record.jsonObject
            val r = fields.getValue("r").jsonArray
            val rest = fields.filterKeys { it != "v" && it != "r" }
            return JsonObject(
                rest + mapOf(
                    "value" to (fields["v"] ?: JsonNull),
                    "revision" to JsonObject(
                        mapOf("wall_time_ms" to r[0], "counter" to r[1], "device_id" to r[2]),
                    ),
                ),
            )
        }
        val ledger = LinkedHashMap<String, JsonElement>()
        ledger["version"] = JsonPrimitive(1)
        for ((key, value) in document) {
            when {
                key == "settings" -> ledger[key] = expand(value)
                key == "estimates" -> ledger[key] = value
                key in LedgerRules.COLLECTIONS -> ledger[key] = JsonObject(value.jsonObject.mapValues { (_, record) -> expand(record) })
                else -> ledger[key] = value
            }
        }
        return LedgerRules.normalize(JsonObject(ledger))
    }

    /** A task from its vector fields (`id`, `project_id`, `due`, `start`, `end`, `labels`, `blocked`, `parent` ...). */
    fun task(raw: JsonObject, zone: TimeZone): Task {
        val blocked = raw["blocked"]?.jsonArray.orEmpty().map { pair ->
            val entry = pair.jsonArray
            Task(id = entry[0].jsonPrimitive.long, title = "", done = entry[1].jsonPrimitive.boolean)
        }
        val parent = raw.whole("parent")
        return Task(
            id = raw.getValue("id").jsonPrimitive.long,
            projectId = raw.whole("project_id") ?: 1,
            title = raw.text("title") ?: "Task ${raw.getValue("id").jsonPrimitive.long}",
            done = raw.flag("done"),
            dueDate = iso(raw.text("due"), zone) ?: NULL_DATE,
            startDate = iso(raw.text("start"), zone).orEmpty(),
            endDate = iso(raw.text("end"), zone).orEmpty(),
            position = raw["position"]?.jsonPrimitive?.contentOrNull?.toDouble() ?: 0.0,
            priority = raw["priority"]?.jsonPrimitive?.int ?: 0,
            isFavorite = raw.flag("flagged"),
            created = iso(raw.text("created"), zone).orEmpty(),
            labels = raw["labels"]?.jsonArray.orEmpty().map { Label(id = it.jsonPrimitive.long, title = "") },
            relatedTasks = buildMap {
                put("blocked", blocked)
                put("parenttask", if (parent != null) listOf(Task(id = parent, title = "")) else emptyList())
            },
        )
    }

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())

    /** A projects list `[{ id, parent_project_id? }]`. */
    fun projects(array: JsonElement?): List<AreaProject> = array!!.jsonArray.map {
        val project = it.jsonObject
        AreaProject(project.getValue("id").jsonPrimitive.long, project.whole("parent_project_id"))
    }

    fun prefs(value: JsonElement?): Map<String, JsonElement?> = value?.jsonObject?.toMap() ?: emptyMap()

    fun longsOf(element: JsonElement?): List<Long> = element!!.jsonArray.map { it.jsonPrimitive.long }
}
