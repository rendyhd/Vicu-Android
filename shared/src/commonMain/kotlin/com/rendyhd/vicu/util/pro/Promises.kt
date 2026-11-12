package com.rendyhd.vicu.util.pro

import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.DueDates
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** "Ask me tomorrow", once per due day: `pro_verdict_snooze` on the device. */
data class VerdictSnooze(
    /** The effective due day the snooze was used for. */
    val due: LocalDate,
    /** Asked again from this day. */
    val until: LocalDate,
)

/** A verdict and what it writes to the task. */
data class VerdictChange(
    val verdict: JsonObject,
    /** The merge patch for the task (JSON field names), or null when the task does not change. */
    val taskPatch: JsonObject?,
)

data class WaitingGroup(
    /** The person's label id; null for tasks without one. */
    val person: Long?,
    val tasks: List<Long>,
)

/**
 * Promises (docs/cross-app-semantics-v1.md section 21.6), the same as the desktop's
 * `src/shared/pro/promises.ts`: a task someone promised you (`theirs`) or you owe someone (`mine`),
 * with its details in the ledger. No stages: a promise is promised, then it gets a verdict. A
 * promise is a ledger value ([JsonObject]).
 */
object Promises {
    val VERDICT_KINDS: List<String> = listOf("delivered", "late", "not_yet", "renegotiated", "dropped", "taken_over")

    private val LOCAL_DATE = Regex("^\\d{4}-\\d{2}-\\d{2}\$")
    private val CONFIRM_TITLE = Regex("^Confirm with ([^:]+):\\s*(.+)\$")

    fun promiseOf(ledger: ProLedger?, taskId: Long): JsonObject? = ledger?.value("promises", taskId)

    /** Misses: active `not_yet` verdicts. Counted, never stored, so an Undo counts right. */
    fun missesOf(promise: JsonObject?): Int =
        LedgerRules.activeVerdicts(promise).count { it.text("kind") == "not_yet" }

    private fun dueDayOf(task: Task, zone: TimeZone): LocalDate? = DueDates.localDateOf(task.dueDate, zone)

    /**
     * The day the promise is due now: the last active verdict's new date; a `not_yet` without one
     * asks again the day after it was given; otherwise the task's due date. Null when there is none.
     */
    fun effectiveDue(task: Task, promise: JsonObject?, zone: TimeZone): LocalDate? {
        val last = LedgerRules.activeVerdicts(promise).lastOrNull()
        val kind = last?.text("kind")
        if (last != null && (kind == "not_yet" || kind == "renegotiated")) {
            val newDue = last.text("new_due")
            if (newDue != null && LOCAL_DATE.matches(newDue)) runCatching { LocalDate.parse(newDue) }.getOrNull()?.let { return it }
            if (kind == "not_yet") {
                DueDates.localDateOf(last.text("at"), zone)?.let { return it.plus(1, DateTimeUnit.DAY) }
            }
        }
        return dueDayOf(task, zone)
    }

    fun readVerdictSnoozes(value: JsonElement?): Map<String, VerdictSnooze> {
        val stored = value as? JsonObject ?: return emptyMap()
        val snoozes = LinkedHashMap<String, VerdictSnooze>()
        for ((id, entry) in stored) {
            val snooze = entry as? JsonObject ?: continue
            val due = snooze.text("due")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: continue
            val until = snooze.text("until")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: continue
            snoozes[id] = VerdictSnooze(due, until)
        }
        return snoozes
    }

    /**
     * Decision 10: a `theirs` promise whose effective due day has come must get a verdict, unless it
     * was snoozed for that day. Open tasks only; a done task is "completed elsewhere" instead.
     */
    fun needsVerdict(task: Task, promise: JsonObject?, today: LocalDate, snooze: VerdictSnooze?, zone: TimeZone): Boolean {
        if (promise == null || promise.text("dir") != "theirs" || task.done) return false
        if (LedgerRules.finalVerdictOf(promise) != null) return false
        val due = effectiveDue(task, promise, zone)
        if (due == null || due > today) return false
        if (snooze != null && snooze.due == due && today < snooze.until) return false
        return true
    }

    /** "Ask me tomorrow" is offered once per due day. */
    fun canSnooze(task: Task, promise: JsonObject?, snooze: VerdictSnooze?, zone: TimeZone): Boolean {
        val due = effectiveDue(task, promise, zone)
        return due != null && (snooze == null || snooze.due != due)
    }

    fun snoozeFor(task: Task, promise: JsonObject?, today: LocalDate, zone: TimeZone): VerdictSnooze? {
        val due = effectiveDue(task, promise, zone) ?: return null
        return VerdictSnooze(due, today.plus(1, DateTimeUnit.DAY))
    }

    /**
     * Decision 11 and section 21.6: a `theirs` promise whose task was completed (here, elsewhere or
     * in the web app) without a final verdict is asked about once.
     */
    fun completedElsewhere(task: Task, promise: JsonObject?): Boolean =
        promise != null && promise.text("dir") == "theirs" && task.done && LedgerRules.finalVerdictOf(promise) == null

    /**
     * A verdict and what it writes to the task: a final verdict completes it (`{ done: true }` only,
     * so Vikunja's repeat still works); a new date moves the due date (date-only, the 16.3 rules apply
     * as for any due change). `taken_over` ("I did it") and an answer about a completed task change
     * nothing. [at] is the UTC instant of the answer.
     */
    fun buildVerdict(
        task: Task,
        kind: String,
        id: String,
        at: String,
        newDue: LocalDate?,
        blocker: String?,
        zone: TimeZone,
    ): VerdictChange {
        val verdict = LinkedHashMap<String, JsonElement>()
        verdict["id"] = JsonPrimitive(id)
        verdict["at"] = JsonPrimitive(at)
        verdict["kind"] = JsonPrimitive(kind)
        val dated = (kind == "not_yet" || kind == "renegotiated") && newDue != null
        if (dated && newDue != null) verdict["new_due"] = JsonPrimitive(newDue.toString())
        if (kind == "not_yet" && blocker != null && blocker.isNotBlank()) verdict["blocker"] = JsonPrimitive(blocker.trim())
        var taskPatch: JsonObject? = null
        if (!task.done && (kind == "delivered" || kind == "late" || kind == "dropped" || kind == "taken_over")) {
            taskPatch = JsonObject(mapOf("done" to JsonPrimitive(true)))
        } else if (!task.done && dated && newDue != null) {
            val due: Instant = DueDates.dateOnlyDue(newDue, zone)
            val current = DateUtils.parseIsoDate(task.dueDate)
            if (current == null || current != due) taskPatch = JsonObject(mapOf("due_date" to JsonPrimitive(due.toString())))
        }
        return VerdictChange(JsonObject(verdict), taskPatch)
    }

    /** The promise with one more verdict. */
    fun withVerdict(promise: JsonObject, verdict: JsonObject): JsonObject {
        val verdicts = promise.list("verdicts").orEmpty()
        return promise.with("verdicts", JsonArray(verdicts + verdict))
    }

    /** Undo: the verdict stays, marked undone, so a merge cannot bring it back. */
    fun withVerdictUndone(promise: JsonObject, verdictId: String): JsonObject {
        val verdicts = promise.list("verdicts").orEmpty().map { entry ->
            val verdict = entry.asObject()
            if (verdict != null && verdict.text("id") == verdictId) verdict.with("undone", JsonPrimitive(true)) else entry
        }
        return promise.with("verdicts", JsonArray(verdicts))
    }

    /** Decision 7: the promise marked as sent in a recap at [at]. */
    fun withSent(promise: JsonObject, at: String): JsonObject {
        val sent = (promise.texts("sent") + at).toSet().sorted()
        return promise.with("sent", JsonArray(sent.map(::JsonPrimitive)))
    }

    fun isSent(promise: JsonObject?): Boolean = (promise?.list("sent")?.size ?: 0) > 0

    // --- Waiting on (section 21.6) ---------------------------------------------------------

    private data class Row(val id: Long, val person: Long?, val due: LocalDate?)

    private val byDue = Comparator<Row> { a, b ->
        when {
            a.due == b.due -> a.id.compareTo(b.id)
            a.due == null -> 1
            b.due == null -> -1
            a.due < b.due -> -1
            else -> 1
        }
    }

    /**
     * Waiting on: open `theirs` promises and open tasks with an on-hold label, grouped by person
     * (the promise's person, else the first person label on the task, else none), groups by their
     * earliest effective due day (none last), then person id, the group without a person last; tasks
     * by effective due day (none last), then id.
     */
    fun waitingOn(
        tasks: List<Task>,
        ledger: ProLedger?,
        isOnHoldLabel: (Long) -> Boolean,
        isPerson: (Long) -> Boolean,
        zone: TimeZone,
    ): List<WaitingGroup> {
        val rows = mutableListOf<Row>()
        for (task in tasks) {
            if (task.done) continue
            val promise = promiseOf(ledger, task.id)
            val theirs = promise?.text("dir") == "theirs" && LedgerRules.finalVerdictOf(promise) == null
            val held = task.labels.any { isOnHoldLabel(it.id) }
            if (!theirs && !held) continue
            val person = (if (theirs) promise?.whole("person") else null) ?: task.labels.firstOrNull { isPerson(it.id) }?.id
            rows.add(Row(task.id, person, if (theirs) effectiveDue(task, promise, zone) else dueDayOf(task, zone)))
        }
        val groups = LinkedHashMap<Long?, MutableList<Row>>()
        for (row in rows) groups.getOrPut(row.person) { mutableListOf() }.add(row)
        data class Group(val person: Long?, val tasks: List<Long>, val first: LocalDate?)
        val result = groups.map { (person, list) ->
            val sorted = list.sortedWith(byDue)
            Group(person, sorted.map { it.id }, sorted.first().due)
        }
        val ordered = result.sortedWith { a, b ->
            val ap = a.person
            val bp = b.person
            when {
                ap == null || bp == null -> if (ap == null) (if (bp == null) 0 else 1) else -1
                a.first != b.first -> when {
                    a.first == null -> 1
                    b.first == null -> -1
                    a.first < b.first -> -1
                    else -> 1
                }
                else -> ap.compareTo(bp)
            }
        }
        return ordered.map { WaitingGroup(it.person, it.tasks) }
    }

    /** Open promises with no "sent" mark (the weekly reminder, decision 7). */
    fun unsentPromises(tasks: List<Task>, ledger: ProLedger?): List<Long> =
        tasks.filter { task ->
            if (task.done) return@filter false
            val promise = promiseOf(ledger, task.id)
            promise != null && LedgerRules.finalVerdictOf(promise) == null && !isSent(promise)
        }.map { it.id }.sorted()

    /** The confirm task of decision 31: "Confirm with Jan: <what>". */
    fun confirmTitle(personName: String, what: String): String = "Confirm with ${personName.trim()}: ${what.trim()}"

    /** The person name and the promise of a confirm task's title, or null when it is not one. */
    fun readConfirmTitle(title: String): Pair<String, String>? {
        val match = CONFIRM_TITLE.find(title.trim()) ?: return null
        return match.groupValues[1].trim() to match.groupValues[2].trim()
    }
}
