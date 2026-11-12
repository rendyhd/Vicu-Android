package com.rendyhd.vicu.util.pro

import com.rendyhd.vicu.util.HiddenTasks
import kotlin.math.floor
import kotlin.math.max
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The Pro ledger (docs/cross-app-semantics-v1.md section 21.7), the same as the desktop's
 * `src/shared/pro/ledger.ts`: one synced document per account holding the details of promises,
 * initiatives, the logs, meetings and estimates. It is kept as decoded JSON so a field this version
 * does not know survives at every level (section 10.8); the typed reads are the functions below and
 * in the sibling files.
 *
 * Records are `{ value, revision }`, `null` a tombstone. Promise verdicts and "sent" marks are
 * unions, so two devices never lose one. Estimates are `[minutes | null, wall time in seconds]`.
 */
class ProLedger(val json: JsonObject) {
    /** The records of a collection by id (`{ value, revision }` objects). */
    fun records(collection: String): JsonObject = json[collection].asObject() ?: EMPTY_OBJECT

    /** The value of a record, or null for a tombstone or a missing record. */
    fun value(collection: String, id: Long): JsonObject? = value(collection, id.toString())

    fun value(collection: String, id: String): JsonObject? =
        records(collection)[id].asObject()?.get("value").asObject()

    /** Every live (not deleted) value of a collection by id. */
    fun values(collection: String): Map<String, JsonObject> =
        records(collection).mapNotNull { (id, record) ->
            record.asObject()?.get("value").asObject()?.let { id to it }
        }.toMap()

    val estimates: JsonObject get() = json["estimates"].asObject() ?: EMPTY_OBJECT

    /** The ledger settings record, or null. */
    val settingsRecord: JsonObject? get() = json["settings"].asObject()

    /** The estimate of a task in minutes, or null. */
    fun estimateOf(taskId: Long): Int? {
        val minutes = (estimates[taskId.toString()] as? JsonArray)?.getOrNull(0).asNumber()
        return if (minutes != null && minutes > 0) minutes.toInt() else null
    }

    /** The estimate entry's minutes as stored (any number), or null for none or cleared. */
    fun storedMinutes(taskId: Long): Double? = (estimates[taskId.toString()] as? JsonArray)?.getOrNull(0).asNumber()

    override fun equals(other: Any?): Boolean = other is ProLedger && other.json == json
    override fun hashCode(): Int = json.hashCode()
    override fun toString(): String = json.toString()

    companion object {
        private val EMPTY_OBJECT = JsonObject(emptyMap())
    }
}

/** A revision `{ wall_time_ms, counter, device_id }` read from a record. */
data class LedgerRevision(val wallTimeMs: Double, val counter: Long, val deviceId: String) {
    fun toJson(): JsonObject = JsonObject(
        mapOf(
            "wall_time_ms" to JsonPrimitive(if (wallTimeMs == floor(wallTimeMs)) wallTimeMs.toLong() else wallTimeMs),
            "counter" to JsonPrimitive(counter),
            "device_id" to JsonPrimitive(deviceId),
        ),
    )
}

/** What an archive pass moves: the records to add to each quarter's part, and the ones to tombstone. */
data class ArchivePlan(
    /** By quarter, then collection, then id: the records to add to that archive part. */
    val parts: Map<String, Map<String, Map<String, JsonObject>>>,
    val moved: List<MovedRecord>,
)

data class MovedRecord(val collection: String, val id: String)

object LedgerRules {
    const val VERSION = 1

    /** The collections made of records, in the order the contract lists them. */
    val COLLECTIONS: List<String> =
        listOf("promises", "initiatives", "log", "owed", "escalations", "meetings", "rounds", "people")

    /** Final kinds close a promise; `not_yet` and `renegotiated` keep it open with a new date. */
    val FINAL_VERDICT_KINDS: List<String> = listOf("delivered", "late", "dropped", "taken_over")

    const val DEFAULT_CAP = 4
    const val DEFAULT_ESCALATION_THRESHOLD = 2

    /** Records closed for longer than this move to the archive parts. */
    const val ARCHIVE_AFTER_DAYS = 180

    private val LOCAL_DATE = Regex("^\\d{4}-\\d{2}-\\d{2}\$")

    fun empty(): ProLedger = ProLedger(
        JsonObject(
            buildMap<String, JsonElement> {
                put("version", JsonPrimitive(VERSION))
                for (collection in COLLECTIONS) put(collection, JsonObject(emptyMap()))
                put("estimates", JsonObject(emptyMap()))
            },
        ),
    )

    // --- Reading ---------------------------------------------------------------------------

    /** Checks a decoded document and fills missing collections; anything that does not fit throws. */
    fun normalize(value: JsonElement): ProLedger {
        val document = value.asObject() ?: throw IllegalArgumentException("Pro ledger is invalid")
        require(document.whole("version") == VERSION.toLong()) { "Pro ledger version mismatch" }
        val filled = document.toMutableMap()
        for (collection in COLLECTIONS) {
            val records = filled[collection]
            if (records == null) {
                filled[collection] = JsonObject(emptyMap())
                continue
            }
            val map = records.asObject() ?: throw IllegalArgumentException("Pro ledger $collection is invalid")
            for ((id, record) in map) {
                if (revisionOf(record.asObject()?.get("revision")) == null) {
                    throw IllegalArgumentException("Pro ledger $collection.$id is invalid")
                }
            }
        }
        val estimates = filled["estimates"]
        if (estimates == null) {
            filled["estimates"] = JsonObject(emptyMap())
        } else {
            val map = estimates.asObject() ?: throw IllegalArgumentException("Pro ledger estimates are invalid")
            for ((id, entry) in map) {
                if (!isEstimateEntry(entry)) throw IllegalArgumentException("Pro ledger estimate $id is invalid")
            }
        }
        val settings = filled["settings"]
        if (settings != null && revisionOf(settings.asObject()?.get("revision")) == null) {
            throw IllegalArgumentException("Pro ledger settings are invalid")
        }
        return ProLedger(JsonObject(filled))
    }

    private fun isEstimateEntry(value: JsonElement): Boolean {
        val array = value as? JsonArray ?: return false
        if (array.size < 2) return false
        val first = array[0]
        return (first is JsonNull || first.asNumber() != null) && array[1].asNumber() != null
    }

    /** A well-formed revision, or null. */
    fun revisionOf(value: JsonElement?): LedgerRevision? {
        val revision = value.asObject() ?: return null
        val wall = revision.number("wall_time_ms") ?: return null
        val counter = revision.whole("counter")?.takeIf { it >= 0 } ?: return null
        val device = revision.text("device_id")?.takeIf { it.isNotEmpty() } ?: return null
        return LedgerRevision(wall, counter, device)
    }

    fun compareRevisions(left: LedgerRevision, right: LedgerRevision): Int {
        val wall = left.wallTimeMs.compareTo(right.wallTimeMs)
        if (wall != 0) return wall
        val counter = left.counter.compareTo(right.counter)
        if (counter != 0) return counter
        return left.deviceId.compareTo(right.deviceId)
    }

    private fun revisionOfRecord(record: JsonElement?): LedgerRevision =
        revisionOf(record.asObject()?.get("revision")) ?: LedgerRevision(0.0, 0, "")

    /** The cap and the escalation threshold, with their defaults, and the settings value. */
    data class Settings(val cap: Int, val threshold: Int, val value: JsonObject)

    fun settings(ledger: ProLedger): Settings {
        val value = ledger.settingsRecord?.get("value").asObject() ?: JsonObject(emptyMap())
        val cap = value.whole("cap")?.takeIf { it >= 1 }?.toInt() ?: DEFAULT_CAP
        val threshold = value.whole("threshold")?.takeIf { it >= 1 }?.toInt() ?: DEFAULT_ESCALATION_THRESHOLD
        return Settings(cap, threshold, value)
    }

    // --- Merge (section 21.7) --------------------------------------------------------------

    private fun verdictsOf(promise: JsonObject): List<JsonObject>? = promise.list("verdicts")?.mapNotNull { it.asObject() }

    /** Verdicts of both sides by id; an undone verdict wins, else the side whose record won. */
    private fun unionVerdicts(winner: List<JsonObject>?, loser: List<JsonObject>?): List<JsonObject>? {
        if (winner == null && loser == null) return null
        val byId = LinkedHashMap<String, JsonObject>()
        for (verdict in loser.orEmpty()) byId[verdict.text("id").orEmpty()] = verdict
        for (verdict in winner.orEmpty()) {
            val id = verdict.text("id").orEmpty()
            val other = byId[id]
            byId[id] = if (other != null && other["undone"].asFlag() == true && verdict["undone"].asFlag() != true) other else verdict
        }
        return byId.values.sortedWith { a, b ->
            val at = a.text("at").orEmpty().compareTo(b.text("at").orEmpty())
            if (at != 0) at else a.text("id").orEmpty().compareTo(b.text("id").orEmpty())
        }
    }

    private fun unionSent(winner: List<String>?, loser: List<String>?): List<String>? {
        if (winner == null && loser == null) return null
        return (winner.orEmpty() + loser.orEmpty()).toSet().sorted()
    }

    private fun mergeRecords(collection: String, local: JsonObject, server: JsonObject): JsonObject {
        val merged = LinkedHashMap<String, JsonElement>()
        for (id in (local.keys + server.keys)) {
            val mine = local[id]
            val theirs = server[id]
            if (mine == null || theirs == null) {
                merged[id] = (mine ?: theirs)!!
                continue
            }
            val localWins = compareRevisions(revisionOfRecord(mine), revisionOfRecord(theirs)) > 0
            val winner = if (localWins) mine else theirs
            val loser = if (localWins) theirs else mine
            val winnerValue = winner.asObject()?.get("value").asObject()
            val loserValue = loser.asObject()?.get("value").asObject()
            if (collection == "promises" && winnerValue != null && loserValue != null) {
                var value = winnerValue
                val verdicts = unionVerdicts(verdictsOf(winnerValue), verdictsOf(loserValue))
                val sent = unionSent(
                    winnerValue.list("sent")?.mapNotNull { it.asText() },
                    loserValue.list("sent")?.mapNotNull { it.asText() },
                )
                if (verdicts != null) value = value.with("verdicts", JsonArray(verdicts))
                if (sent != null) value = value.with("sent", JsonArray(sent.map(::JsonPrimitive)))
                merged[id] = winner.asObject()!!.with("value", value)
            } else {
                merged[id] = winner
            }
        }
        return JsonObject(merged)
    }

    private fun estimateWall(entry: JsonElement?): Double = ((entry as? JsonArray)?.getOrNull(1)).asNumber() ?: 0.0

    private fun mergeEstimates(local: JsonObject, server: JsonObject): JsonObject {
        val merged = LinkedHashMap<String, JsonElement>()
        for (id in (local.keys + server.keys)) {
            val mine = local[id]
            val theirs = server[id]
            merged[id] = when {
                mine == null -> theirs!!
                theirs == null -> mine
                estimateWall(mine) > estimateWall(theirs) -> mine
                else -> theirs
            }
        }
        return JsonObject(merged)
    }

    /**
     * `merge(local, server)`: per record the newer revision wins (equal revisions keep the server's),
     * except that two values of a promise keep the union of their verdicts (by id) and "sent" marks.
     * Estimates: the newer wall time wins. Unknown top-level fields are the union, the server's kept.
     * No pruning here.
     */
    fun merge(local: ProLedger, server: ProLedger): ProLedger {
        val merged = LinkedHashMap<String, JsonElement>()
        merged.putAll(local.json)
        merged.putAll(server.json)
        for (collection in COLLECTIONS) {
            merged[collection] = mergeRecords(collection, local.records(collection), server.records(collection))
        }
        merged["estimates"] = mergeEstimates(local.estimates, server.estimates)
        val mine = local.settingsRecord
        val theirs = server.settingsRecord
        val settings = when {
            mine == null -> theirs
            theirs == null -> mine
            compareRevisions(revisionOfRecord(mine), revisionOfRecord(theirs)) > 0 -> mine
            else -> theirs
        }
        if (settings != null) merged["settings"] = settings else merged.remove("settings")
        return ProLedger(JsonObject(merged))
    }

    private fun isExpiredEstimate(entry: JsonElement, nowMs: Long): Boolean {
        val cleared = (entry as? JsonArray)?.getOrNull(0) is JsonNull
        return cleared && estimateWall(entry) * 1000 < nowMs - HiddenTasks.TOMBSTONE_MAX_AGE_MS
    }

    private fun isExpiredTombstone(record: JsonElement, nowMs: Long): Boolean {
        val value = record.asObject()?.get("value")
        if (value != null && value !is JsonNull) return false
        return revisionOfRecord(record).wallTimeMs < nowMs - HiddenTasks.TOMBSTONE_MAX_AGE_MS
    }

    /** The document without tombstones (records and cleared estimates) older than 90 days before [nowMs]. */
    fun prune(ledger: ProLedger, nowMs: Long): ProLedger {
        val pruned = LinkedHashMap<String, JsonElement>(ledger.json)
        for (collection in COLLECTIONS) {
            pruned[collection] = JsonObject(ledger.records(collection).filterValues { !isExpiredTombstone(it, nowMs) })
        }
        pruned["estimates"] = JsonObject(
            ledger.estimates.filterValues { entry -> !isExpiredEstimate(entry, nowMs) },
        )
        return ProLedger(JsonObject(pruned))
    }

    /** True when the document holds anything (only then is a carrier created). */
    fun hasContent(ledger: ProLedger): Boolean =
        COLLECTIONS.any { ledger.records(it).isNotEmpty() } || ledger.estimates.isNotEmpty() || ledger.settingsRecord != null

    // --- Changes -----------------------------------------------------------------------------

    private fun allRevisions(ledger: ProLedger): List<LedgerRevision> {
        val revisions = mutableListOf<LedgerRevision>()
        for (collection in COLLECTIONS) {
            for (record in ledger.records(collection).values) {
                revisionOf(record.asObject()?.get("revision"))?.let(revisions::add)
            }
        }
        ledger.settingsRecord?.get("revision")?.let { revisionOf(it) }?.let(revisions::add)
        return revisions
    }

    /** The revision for a change made now on this device: newer than every revision in the document. */
    fun nextRevision(ledger: ProLedger, deviceId: String, now: Long): LedgerRevision {
        val revisions = allRevisions(ledger)
        val maxWall = revisions.maxOfOrNull { it.wallTimeMs } ?: 0.0
        val wall = max(now.toDouble(), maxWall)
        val maxCounter = revisions.filter { it.wallTimeMs == wall }.maxOfOrNull { it.counter } ?: -1L
        return LedgerRevision(wall, if (wall == now.toDouble() && now > maxWall) 0 else maxCounter + 1, deviceId)
    }

    /** The document with one record set (a null value deletes it). The record's unknown fields stay. */
    fun setRecord(ledger: ProLedger, collection: String, id: String, value: JsonObject?, deviceId: String, now: Long): ProLedger {
        val revision = nextRevision(ledger, deviceId, now)
        val records = ledger.records(collection)
        val previous = records[id].asObject() ?: JsonObject(emptyMap())
        val record = previous.with("value", value ?: JsonNull).with("revision", revision.toJson())
        return ProLedger(ledger.json.with(collection, records.with(id, record)))
    }

    fun setSettings(ledger: ProLedger, value: JsonObject, deviceId: String, now: Long): ProLedger {
        val revision = nextRevision(ledger, deviceId, now)
        val record = (ledger.settingsRecord ?: JsonObject(emptyMap())).with("value", value).with("revision", revision.toJson())
        return ProLedger(ledger.json.with("settings", record))
    }

    /** Sets (or, with null [minutes], clears) a task's estimate; [now] is in milliseconds. */
    fun setEstimate(ledger: ProLedger, taskId: Long, minutes: Int?, now: Long): ProLedger {
        val previous = ledger.estimates[taskId.toString()]
        val wall = max(now / 1000, if (previous != null) estimateWall(previous).toLong() + 1 else 0L)
        val stored: JsonElement = if (minutes != null && minutes > 0) JsonPrimitive(minutes) else JsonNull
        val entry = JsonArray(listOf(stored, JsonPrimitive(wall)))
        return ProLedger(ledger.json.with("estimates", ledger.estimates.with(taskId.toString(), entry)))
    }

    /**
     * A task created offline has a temporary (negative) id until the replay creates it. Renames every
     * place the ledger names that task: the promise and estimate keys, needs, escalations and log tasks.
     */
    fun remapTaskId(ledger: ProLedger, from: Long, to: Long): ProLedger {
        fun renameKey(id: String): String = if (id == from.toString()) to.toString() else id
        val fromJson = JsonPrimitive(from)
        val toJson = JsonPrimitive(to)
        val promises = LinkedHashMap<String, JsonElement>()
        for ((id, record) in ledger.records("promises")) {
            val value = record.asObject()?.get("value").asObject()
            val needs = value?.list("needs")
            promises[renameKey(id)] = if (value != null && needs != null && needs.any { it == fromJson }) {
                record.asObject()!!.with("value", value.with("needs", JsonArray(needs.map { if (it == fromJson) toJson else it })))
            } else {
                record
            }
        }
        val estimates = LinkedHashMap<String, JsonElement>()
        for ((id, entry) in ledger.estimates) estimates[renameKey(id)] = entry
        val escalations = LinkedHashMap<String, JsonElement>()
        for ((id, record) in ledger.records("escalations")) {
            val value = record.asObject()?.get("value").asObject()
            escalations[id] = if (value != null && listOf("promise", "a", "b").any { value[it] == fromJson }) {
                var next: JsonObject = value
                for (key in listOf("promise", "a", "b")) if (value[key] == fromJson) next = next.with(key, toJson)
                record.asObject()!!.with("value", next)
            } else {
                record
            }
        }
        val log = LinkedHashMap<String, JsonElement>()
        for ((id, record) in ledger.records("log")) {
            val value = record.asObject()?.get("value").asObject()
            log[id] = if (value != null && value["task"] == fromJson) {
                record.asObject()!!.with("value", value.with("task", toJson))
            } else {
                record
            }
        }
        return ProLedger(
            ledger.json
                .with("promises", JsonObject(promises))
                .with("estimates", JsonObject(estimates))
                .with("escalations", JsonObject(escalations))
                .with("log", JsonObject(log)),
        )
    }

    // --- Size and archive (section 21.7) ---------------------------------------------------

    /** The quarter a local date is in: `2026-Q3`. */
    fun quarterOf(day: LocalDate): String = "${day.year}-Q${day.month.ordinal / 3 + 1}"

    /** The verdicts that count: not undone, in the order given. */
    fun activeVerdicts(promise: JsonObject?): List<JsonObject> =
        promise?.list("verdicts")?.mapNotNull { it.asObject() }?.filter { it["undone"].asFlag() != true } ?: emptyList()

    /** The final verdict that closed a promise, or null while it is open. */
    fun finalVerdictOf(promise: JsonObject?): JsonObject? {
        val last = activeVerdicts(promise).lastOrNull() ?: return null
        return if (last.text("kind") in FINAL_VERDICT_KINDS) last else null
    }

    private fun localDateOrNull(value: JsonElement?): LocalDate? {
        val text = value.asText() ?: return null
        if (!LOCAL_DATE.matches(text)) return null
        return runCatching { LocalDate.parse(text) }.getOrNull()
    }

    /** The local day a record was closed, or null while it is open (or never closes). */
    fun closedOn(collection: String, value: JsonObject?, zone: TimeZone): LocalDate? {
        value ?: return null
        return when (collection) {
            "promises" -> finalVerdictOf(value)?.let { localDayOf(it.text("at"), zone) }
            "initiatives" -> if (value.text("status") == "closed") localDateOrNull(value["closed_on"]) else null
            "log" -> if (value.text("kind") == "unknown") localDateOrNull(value["resolved"]) else localDateOrNull(value["at"])
            "owed", "escalations" -> localDateOrNull(value["resolved"])
            "rounds" -> localDateOrNull(value["at"])
            else -> null
        }
    }

    /** Records closed more than 180 days before [today], by the quarter they were closed in. */
    fun archivePlan(ledger: ProLedger, today: LocalDate, zone: TimeZone): ArchivePlan {
        val parts = LinkedHashMap<String, LinkedHashMap<String, LinkedHashMap<String, JsonObject>>>()
        val moved = mutableListOf<MovedRecord>()
        for (collection in COLLECTIONS) {
            for ((id, record) in ledger.records(collection)) {
                val recordObject = record.asObject() ?: continue
                val day = closedOn(collection, recordObject["value"].asObject(), zone) ?: continue
                if (day.daysUntil(today) <= ARCHIVE_AFTER_DAYS) continue
                parts.getOrPut(quarterOf(day)) { LinkedHashMap() }
                    .getOrPut(collection) { LinkedHashMap() }[id] = recordObject
                moved.add(MovedRecord(collection, id))
            }
        }
        moved.sortWith(compareBy<MovedRecord> { it.collection }.thenBy { it.id })
        return ArchivePlan(parts, moved)
    }

    /** The document with the moved records replaced by tombstones (one revision for all of them). */
    fun tombstoneMoved(ledger: ProLedger, moved: List<MovedRecord>, deviceId: String, now: Long): ProLedger {
        if (moved.isEmpty()) return ledger
        val revision = nextRevision(ledger, deviceId, now).toJson()
        var next = ledger.json
        for ((collection, id) in moved) {
            val records = next[collection].asObject() ?: JsonObject(emptyMap())
            val previous = records[id].asObject() ?: JsonObject(emptyMap())
            val record = previous.with("value", JsonNull).with("revision", revision)
            next = next.with(collection, records.with(id, record))
        }
        return ProLedger(next)
    }

    /** An archive part's document: the moved records of one quarter. */
    fun emptyArchivePart(quarter: String): JsonObject =
        JsonObject(mapOf("version" to JsonPrimitive(VERSION), "quarter" to JsonPrimitive(quarter)))

    /** An archive part with more records added (a record already there keeps the newer revision). */
    fun addToArchivePart(part: JsonObject, records: Map<String, Map<String, JsonObject>>): JsonObject {
        var next = part
        for (collection in COLLECTIONS) {
            val incoming = records[collection] ?: continue
            val existing = part[collection].asObject() ?: JsonObject(emptyMap())
            next = next.with(collection, mergeRecords(collection, existing, JsonObject(incoming)))
        }
        return next
    }
}
