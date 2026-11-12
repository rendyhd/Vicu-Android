package com.rendyhd.vicu.util.pro

import com.rendyhd.vicu.domain.model.Task
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** An active initiative with its rank shown as 1, 2, 3 ... */
data class RankedInitiative(val projectId: Long, val rank: Int, val value: JsonObject)

data class CapState(
    val active: Int,
    val cap: Int,
    val agreed: Boolean,
    /** Adding one more needs one parked first. */
    val full: Boolean,
)

enum class ReadinessItem(val key: String, val label: String) {
    SPONSOR("sponsor", "Sponsor"),
    OWNER("owner", "Owner"),
    WHY("why", "Why now"),
    DONE_LOOKS_LIKE("done_looks_like", "Done looks like"),
    SMALLEST("smallest", "Smallest useful step"),
    NOT_DOING("not_doing", "Not doing"),
    DECISION_RIGHTS("decision_rights", "Who decides what"),
    CAPACITY("capacity", "Capacity agreed"),
    STOP_IF("stop_if", "Stop if"),
}

data class ReadinessResult(
    val done: Int,
    val total: Int,
    val missing: List<ReadinessItem>,
) {
    val ready: Boolean get() = missing.isEmpty()
}

/**
 * The portfolio (docs/cross-app-semantics-v1.md section 21.10), the same as the desktop's
 * `src/shared/pro/portfolio.ts`: ranked initiatives, the cap agreed with the sponsor, parking,
 * readiness, "log anyway", and escalations drafted on the second miss of a promise. Initiatives and
 * escalations are ledger values ([JsonObject]).
 */
object Portfolio {
    private const val NO_RANK = Long.MAX_VALUE

    /** Active initiatives by rank (lowest first), then project id; ranks shown as 1, 2, 3 ... */
    fun rankedInitiatives(ledger: ProLedger?): List<RankedInitiative> {
        class Entry(val projectId: Long, val rank: Double, val value: JsonObject)

        val active = mutableListOf<Entry>()
        for ((id, value) in ledger?.values("initiatives").orEmpty()) {
            if ((value.text("status") ?: "active") != "active") continue
            val projectId = id.toLongOrNull() ?: continue
            active.add(Entry(projectId, value.number("rank") ?: NO_RANK.toDouble(), value))
        }
        active.sortWith(compareBy<Entry> { it.rank }.thenBy { it.projectId })
        return active.mapIndexed { index, entry -> RankedInitiative(entry.projectId, index + 1, entry.value) }
    }

    /** The ranks after moving one active initiative to a 0-based place: every active one, 1 to n. */
    fun moveRank(ledger: ProLedger, projectId: Long, toIndex: Int): Map<Long, Int> {
        val order = rankedInitiatives(ledger).map { it.projectId }.toMutableList()
        val from = order.indexOf(projectId)
        if (from < 0) return emptyMap()
        order.removeAt(from)
        order.add(toIndex.coerceIn(0, order.size), projectId)
        return order.withIndex().associate { it.value to it.index + 1 }
    }

    fun capState(ledger: ProLedger?): CapState {
        val settings = ledger?.let { LedgerRules.settings(it) }
        val cap = settings?.cap ?: LedgerRules.DEFAULT_CAP
        val agreedValue = settings?.value?.get("cap_agreed")
        val agreed = agreedValue != null && agreedValue !is JsonNull && agreedValue != JsonPrimitive(false)
        val active = rankedInitiatives(ledger).size
        return CapState(active, cap, agreed, active >= cap)
    }

    /** Parking: status parked with the day and reason; the rank is dropped (it comes back last). */
    fun parkedValue(value: JsonObject, on: LocalDate, reason: String): JsonObject {
        val parked = JsonObject(mapOf("on" to JsonPrimitive(on.toString()), "reason" to JsonPrimitive(reason.trim())))
        return value.with("status", JsonPrimitive("parked")).with("parked", parked).without("rank")
    }

    /** Back to active, ranked last. */
    fun unparkedValue(ledger: ProLedger, value: JsonObject): JsonObject =
        value.with("status", JsonPrimitive("active"))
            .with("rank", JsonPrimitive(rankedInitiatives(ledger).size + 1))
            .without("parked")

    // --- Readiness (decision 16) -----------------------------------------------------------

    private fun filled(value: JsonElement?): Boolean = when {
        value == null || value is JsonNull -> false
        value is JsonPrimitive && value.isString -> value.content.trim().isNotEmpty()
        else -> true
    }

    /** The nine items; text items count when not blank, capacity when agreed by someone on a day. */
    fun readiness(value: JsonObject?): ReadinessResult {
        val ready = value?.obj("ready") ?: JsonObject(emptyMap())
        val missing = ReadinessItem.entries.filter { item ->
            when (item) {
                ReadinessItem.SPONSOR, ReadinessItem.OWNER -> value?.get(item.key).asNumber() == null
                ReadinessItem.CAPACITY -> ready.obj("capacity")?.get("on").asText() == null
                else -> !filled(ready[item.key])
            }
        }
        return ReadinessResult(ReadinessItem.entries.size - missing.size, ReadinessItem.entries.size, missing)
    }

    /** "Log anyway?": capture into an initiative that is not ready is allowed and counted. */
    fun withLogAnyway(value: JsonObject): JsonObject =
        value.with("log_anyway", JsonPrimitive((value.whole("log_anyway") ?: 0L) + 1))

    // --- Escalations (decision 15) ---------------------------------------------------------

    /** True when the promise has reached the escalation threshold and has no escalation yet. */
    fun escalationDue(ledger: ProLedger, taskId: Long): Boolean {
        val promise = ledger.value("promises", taskId) ?: return false
        if (promise.text("dir") != "theirs" || LedgerRules.finalVerdictOf(promise) != null) return false
        if (Promises.missesOf(promise) < LedgerRules.settings(ledger).threshold) return false
        return ledger.values("escalations").values.none { it.whole("promise") == taskId }
    }

    private val BY_DUE = Comparator<LocalDate?> { a, b ->
        when {
            a == b -> 0
            a == null -> 1
            b == null -> -1
            a < b -> -1
            else -> 1
        }
    }

    /**
     * The escalation draft: option A is the missed promise; option B is the same person's other open
     * promise of theirs in another initiative, the highest-ranked first (then the earliest due, then
     * the lowest id). Without one, the draft asks for help with A alone. [dateText] is the words for
     * a date in the draft ("Fri 20 Nov").
     */
    fun draftEscalation(
        ledger: ProLedger,
        taskId: Long,
        tasks: Map<Long, Task>,
        personName: (Long) -> String,
        projectName: (Long) -> String,
        dateText: (LocalDate) -> String,
        today: LocalDate,
        zone: TimeZone,
    ): JsonObject? {
        val promise = ledger.value("promises", taskId)
        val taskA = tasks[taskId]
        val person = promise?.whole("person")
        if (promise == null || person == null || taskA == null) return null
        val ranks = rankedInitiatives(ledger).associate { it.projectId to it.rank }

        class Candidate(val id: Long, val rank: Int, val due: LocalDate?)

        val candidates = mutableListOf<Candidate>()
        for ((id, other) in ledger.values("promises")) {
            val otherId = id.toLongOrNull() ?: continue
            val task = tasks[otherId]
            if (otherId == taskId || task == null || task.done) continue
            if (other.text("dir") != "theirs" || other.whole("person") != person || LedgerRules.finalVerdictOf(other) != null) continue
            val initiative = other.whole("initiative") ?: continue
            if (initiative == promise.whole("initiative")) continue
            candidates.add(Candidate(otherId, ranks[initiative] ?: Int.MAX_VALUE, Promises.effectiveDue(task, other, zone)))
        }
        candidates.sortWith(
            Comparator { a, b ->
                val rank = a.rank.compareTo(b.rank)
                if (rank != 0) return@Comparator rank
                val due = BY_DUE.compare(a.due, b.due)
                if (due != 0) due else a.id.compareTo(b.id)
            },
        )
        val b = candidates.firstOrNull()?.id
        val name = personName(person)
        val misses = Promises.missesOf(promise)
        val missedWord = if (misses == 2) "twice" else "$misses times"
        val dueA = Promises.effectiveDue(taskA, promise, zone)
        val initiativeA = promise.whole("initiative")?.let { " (${projectName(it)})" } ?: ""
        val lines = mutableListOf(
            "$name has missed \"${taskA.title.trim()}\"$initiativeA $missedWord${if (dueA != null) "; it is now due ${dateText(dueA)}" else ""}.",
        )
        if (b != null) {
            val other = ledger.value("promises", b)!!
            val taskB = tasks.getValue(b)
            lines.add("$name is also working on \"${taskB.title.trim()}\" (${projectName(other.whole("initiative")!!)}).")
            lines.add("Which comes first? A: \"${taskA.title.trim()}\". B: \"${taskB.title.trim()}\".")
        } else {
            lines.add("Can you help unblock it, or should we change what was agreed?")
        }
        return JsonObject(
            mapOf(
                "promise" to JsonPrimitive(taskId),
                "a" to JsonPrimitive(taskId),
                "b" to (b?.let { JsonPrimitive(it) } ?: JsonNull),
                "status" to JsonPrimitive("drafted"),
                "draft" to JsonPrimitive(lines.joinToString(" ")),
                "decision" to JsonNull,
                "opened" to JsonPrimitive(today.toString()),
                "resolved" to JsonNull,
            ),
        )
    }
}
