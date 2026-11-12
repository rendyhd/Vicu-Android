package com.rendyhd.vicu.util.pro

import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.DateDisplayFormat
import com.rendyhd.vicu.util.withoutNestedSubtasks
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** The keys of a list's Available / Remaining / All choice (`pro_list_views`), as the desktop writes them. */
object ProListKeys {
    const val ANYTIME = "anytime"
    const val FLAGGED = "flagged"
    fun project(id: Long): String = "project:$id"
    fun tag(id: Long): String = "tag:$id"
    fun customList(id: String): String = "list:$id"
}

/** What a list shows under a view, and how many open tasks the view leaves out. */
data class ViewFiltered(val tasks: List<Task>, val hidden: Int)

/** A person on the People screen: what is open both ways and the next due day. */
data class PeopleRow(
    val id: Long,
    val name: String,
    val hexColor: String,
    val theirs: Int,
    val mine: Int,
    val next: LocalDate?,
    val overdue: Int,
)

/** A person page's lists: their promises, yours to them, and the tasks to confirm with them. */
data class PersonSections(
    val theirs: List<Task> = emptyList(),
    val mine: List<Task> = emptyList(),
    val confirm: List<Task> = emptyList(),
)

/** A 1:1 agenda: its lines, and the text the share sheet sends. */
data class Agenda(val items: List<String>, val text: String)

/**
 * What the Pro screens on the phone show (docs/cross-app-semantics-v1.md section 21, design system
 * section 9), the same words as the desktop's rows, views and person page. Pure: the screens pass
 * the day, the zone and the promise of each task.
 */
object ProScreens {
    /** Pinned first on every 1:1 agenda. */
    const val UNCLEAR_ITEM = "What's still unclear?"

    /** The verdict buttons, in their order; "Ask me tomorrow" follows when it may be used. */
    val VERDICT_ORDER: List<String> = listOf("delivered", "late", "not_yet", "renegotiated", "dropped")

    /** Estimate presets, in minutes. */
    val ESTIMATE_PRESETS: List<Int> = listOf(5, 15, 30, 60, 90, 120, 240)

    fun verdictLabel(kind: String): String = when (kind) {
        "delivered" -> "Delivered"
        "late" -> "Late"
        "not_yet" -> "Not yet"
        "renegotiated" -> "New date"
        "dropped" -> "Dropped"
        "taken_over" -> "I did it"
        else -> kind
    }

    /**
     * The words a row shows for its first reason: "Starts Mon 17 Nov", "On hold", "Waiting",
     * "After: Budget draft" (and "and 2 more"), "Later in sequence", "Their promise". Null when
     * the task is available.
     */
    fun reasonText(
        task: Task,
        availability: TaskAvailability,
        titleOf: (Long) -> String?,
        today: LocalDate,
        zone: TimeZone,
        fmt: DateDisplayFormat,
    ): String? = when (availability.reasons.firstOrNull()) {
        null -> null
        AvailabilityReason.STARTS -> StartDates.startDayOf(task, zone)
            ?.let { "Starts ${DateDisplay.formatWeekdayDate(it, today, fmt)}" } ?: "Starts later"
        AvailabilityReason.ON_HOLD -> "On hold"
        AvailabilityReason.WAITING -> "Waiting"
        AvailabilityReason.AFTER -> {
            val first = availability.blockers.firstOrNull()
            val title = first?.let(titleOf) ?: blockerTitle(task, first)
            val more = if (availability.blockers.size > 1) " and ${availability.blockers.size - 1} more" else ""
            if (title != null) "After: $title$more" else "After another task"
        }
        AvailabilityReason.SEQUENCE -> "Later in sequence"
        AvailabilityReason.THEIRS -> "Their promise"
    }

    private fun blockerTitle(task: Task, id: Long?): String? =
        id?.let { wanted -> task.relatedTasks["blocked"]?.firstOrNull { it.id == wanted }?.title?.takeIf { it.isNotBlank() } }

    /** The line under an Available list. */
    fun hiddenSummary(count: Int): String =
        if (count == 1) "1 task is not available yet." else "$count tasks are not available yet."

    /** "From Jan" for a promise of theirs, "For Jan" for yours; null for anything else. */
    fun promiseMeta(dir: String?, name: String): String? = when (dir) {
        "theirs" -> "From $name"
        "mine" -> "For $name"
        else -> null
    }

    /** The first letters of the first and last words of a name, in capitals; "?" without a name. */
    fun initials(name: String): String {
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return "?"
        val first = words.first().first().toString()
        val last = if (words.size > 1) words.last().first().toString() else ""
        return (first + last).uppercase()
    }

    /** The After chip's value: "After: Budget draft" for one task, "After 3 tasks" for more. */
    fun afterValue(blockerTitles: List<String?>): String? = when (blockerTitles.size) {
        0 -> null
        1 -> "After: ${blockerTitles[0]?.takeIf { it.isNotBlank() } ?: "a task"}"
        else -> "After ${blockerTitles.size} tasks"
    }

    /** The Start sheet's quick choices: tomorrow, next Monday, in two weeks. */
    fun startChoices(today: LocalDate): List<Pair<String, LocalDate>> {
        val monday = today.minus(today.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
        return listOf(
            "Tomorrow" to today.plus(1, DateTimeUnit.DAY),
            "Next week" to monday.plus(7, DateTimeUnit.DAY),
            "In two weeks" to today.plus(14, DateTimeUnit.DAY),
        )
    }

    // --- Lists ---------------------------------------------------------------------------------

    /**
     * The tasks a view shows (contract 21.2): Available keeps the open tasks without a reason,
     * Remaining the open tasks, All everything given. [availabilityOf] answers null for a task it
     * does not judge, which counts as available. [hidden] is how many tasks the view leaves out.
     */
    fun filter(tasks: List<Task>, view: AvailabilityView, availabilityOf: (Task) -> TaskAvailability?): ViewFiltered {
        val kept = when (view) {
            AvailabilityView.ALL -> tasks
            AvailabilityView.REMAINING -> tasks.filter { !it.done }
            AvailabilityView.AVAILABLE -> tasks.filter { !it.done && availabilityOf(it)?.available != false }
        }
        return ViewFiltered(kept, tasks.size - kept.size)
    }

    /** Flagged (contract 21.4): open flagged tasks of active projects, nested subtasks folded under a flagged parent. */
    fun flagged(open: List<Task>, activeProjectIds: Set<Long>): List<Task> =
        open.filter { !it.done && it.isFavorite && it.projectId in activeProjectIds }
            .withoutNestedSubtasks(hideChildrenOfCompletedParents = false)

    // --- People --------------------------------------------------------------------------------

    /**
     * The People screen: every person with their open promises both ways (promises with a final
     * verdict left out), the next effective due day and how many are overdue; the next due first,
     * people without one after them, then by name.
     */
    fun peopleRows(
        people: List<Label>,
        open: List<Task>,
        promiseFor: (Task) -> JsonObject?,
        today: LocalDate,
        zone: TimeZone,
    ): List<PeopleRow> {
        class Counts(var theirs: Int = 0, var mine: Int = 0, var next: LocalDate? = null, var overdue: Int = 0)
        val counts = LinkedHashMap<Long, Counts>()
        for (person in people) counts[person.id] = Counts()
        for (task in open) {
            if (task.done) continue
            val promise = promiseFor(task) ?: continue
            if (LedgerRules.finalVerdictOf(promise) != null) continue
            val entry = promise.whole("person")?.let { counts[it] } ?: continue
            when (promise.text("dir")) {
                "theirs" -> entry.theirs++
                "mine" -> entry.mine++
            }
            val due = Promises.effectiveDue(task, promise, zone)
            if (due != null && (entry.next == null || due < entry.next!!)) entry.next = due
            if (due != null && due < today) entry.overdue++
        }
        return people.map { person ->
            val entry = counts.getValue(person.id)
            PeopleRow(person.id, person.title, person.hexColor, entry.theirs, entry.mine, entry.next, entry.overdue)
        }.sortedWith { a, b ->
            when {
                a.next == b.next -> a.name.lowercase().compareTo(b.name.lowercase())
                a.next == null -> 1
                b.next == null -> -1
                else -> a.next.compareTo(b.next)
            }
        }
    }

    /**
     * A person page: their open promises, yours to them, and the open tasks titled "Confirm with
     * <name>: ..." whose name matches them (decision 31).
     */
    fun personSections(
        personId: Long,
        open: List<Task>,
        promiseFor: (Task) -> JsonObject?,
        people: List<Person>,
    ): PersonSections {
        val theirs = mutableListOf<Task>()
        val mine = mutableListOf<Task>()
        val confirm = mutableListOf<Task>()
        for (task in open) {
            if (task.done) continue
            val promise = promiseFor(task)
            if (promise != null && promise.whole("person") == personId && LedgerRules.finalVerdictOf(promise) == null) {
                when (promise.text("dir")) {
                    "theirs" -> theirs.add(task)
                    "mine" -> mine.add(task)
                }
                continue
            }
            val asked = Promises.readConfirmTitle(task.title) ?: continue
            if (People.matchPerson(asked.first, people).id == personId) confirm.add(task)
        }
        return PersonSections(theirs, mine, confirm)
    }

    /**
     * The 1:1 agenda (plan 7.3.6): "What's still unclear?" first, their promises by effective due
     * day with "(overdue)", "(due today)" and the misses, yours to them as "Mine: ...", then the
     * decisions they owe you from the ledger.
     */
    fun agenda(
        name: String,
        personId: Long,
        theirs: List<Task>,
        mine: List<Task>,
        promiseFor: (Task) -> JsonObject?,
        ledger: ProLedger?,
        today: LocalDate,
        zone: TimeZone,
    ): Agenda {
        val items = mutableListOf(UNCLEAR_ITEM)
        val byDue = Comparator<Task> { a, b ->
            val da = Promises.effectiveDue(a, promiseFor(a), zone)
            val db = Promises.effectiveDue(b, promiseFor(b), zone)
            when {
                da == db -> a.id.compareTo(b.id)
                da == null -> 1
                db == null -> -1
                else -> da.compareTo(db)
            }
        }
        for (task in theirs.sortedWith(byDue)) {
            val promise = promiseFor(task)
            val due = Promises.effectiveDue(task, promise, zone)
            val misses = Promises.missesOf(promise)
            val note = when {
                due != null && due < today -> " (overdue)"
                due == today -> " (due today)"
                else -> ""
            }
            val missed = when (misses) {
                0 -> ""
                1 -> ", missed once"
                else -> ", missed $misses times"
            }
            items.add("${task.title.trim()}$note$missed")
        }
        for (task in mine.sortedWith(byDue)) items.add("Mine: ${task.title.trim()}")
        for ((_, owed) in ledger?.values("owed").orEmpty()) {
            if (owed.whole("from") != personId || owed["resolved"].let { it != null && it !is JsonNull }) continue
            val what = owed.text("what")?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            items.add("Decision needed: $what")
        }
        val title = "1:1 with ${name.trim()}"
        val text = (listOf(title, "") + items.map { "- $it" }).joinToString("\n")
        return Agenda(items, text)
    }
}
