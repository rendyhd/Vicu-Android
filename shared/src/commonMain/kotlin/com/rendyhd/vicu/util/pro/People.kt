package com.rendyhd.vicu.util.pro

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil

/** A label marked as a person, with the aliases remembered for it. */
data class Person(val id: Long, val title: String, val aliases: List<String> = emptyList())

enum class PersonMatchKind(val key: String) {
    TITLE("title"),
    ALIAS("alias"),
    FIRST("first"),
    NONE("none"),
    AMBIGUOUS("ambiguous"),
}

data class PersonMatch(val id: Long?, val match: PersonMatchKind)

/** Promises of one person over the last 90 local days, and what is open now. */
data class PersonCounts(
    /** Promises of theirs closed in the window, by how. */
    val delivered: Int = 0,
    val late: Int = 0,
    val dropped: Int = 0,
    val takenOver: Int = 0,
    /** Verdicts given in the window. */
    val renegotiated: Int = 0,
    val missed: Int = 0,
    /** Open now. */
    val openTheirs: Int = 0,
    val openMine: Int = 0,
)

/**
 * People (docs/cross-app-semantics-v1.md section 21.5), the same as the desktop's
 * `src/shared/pro/people.ts`: labels marked as a person (`label_kind.<id>`), their remembered
 * aliases in the ledger, name matching, and the 90-day counts and pattern of decision 9 (plain
 * words, no score).
 */
object People {
    const val PERSON_WINDOW_DAYS = 90

    private val WHITESPACE = Regex("\\s+")

    /** Lower case, trimmed, inner whitespace as one space. */
    fun normalizeName(name: String): String = name.trim().replace(WHITESPACE, " ").lowercase()

    private fun firstWord(name: String): String = normalizeName(name).split(' ').first()

    /** The people with their aliases from the ledger. */
    fun peopleWithAliases(
        labels: List<Pair<Long, String>>,
        isPerson: (Long) -> Boolean,
        ledger: ProLedger?,
    ): List<Person> = labels.filter { isPerson(it.first) }.map { (id, title) ->
        Person(id, title, ledger?.value("people", id)?.texts("aliases").orEmpty())
    }

    /**
     * A name to a person: the title (case and spacing ignored), then a remembered alias, then a
     * unique first name (the name's first word equals the first word of exactly one person's title,
     * and the name is one word or that person's title starts with the name). Two people with that
     * title or alias is `ambiguous`.
     */
    fun matchPerson(name: String, people: List<Person>): PersonMatch {
        val wanted = normalizeName(name)
        if (wanted.isEmpty()) return PersonMatch(null, PersonMatchKind.NONE)
        val byTitle = people.filter { normalizeName(it.title) == wanted }
        if (byTitle.size == 1) return PersonMatch(byTitle[0].id, PersonMatchKind.TITLE)
        if (byTitle.size > 1) return PersonMatch(null, PersonMatchKind.AMBIGUOUS)
        val byAlias = people.filter { person -> person.aliases.any { normalizeName(it) == wanted } }
        if (byAlias.size == 1) return PersonMatch(byAlias[0].id, PersonMatchKind.ALIAS)
        if (byAlias.size > 1) return PersonMatch(null, PersonMatchKind.AMBIGUOUS)
        val first = firstWord(wanted)
        val byFirst = people.filter { firstWord(it.title) == first }
        if (byFirst.size == 1 && (wanted == first || normalizeName(byFirst[0].title).startsWith(wanted))) {
            return PersonMatch(byFirst[0].id, PersonMatchKind.FIRST)
        }
        if (byFirst.size > 1) return PersonMatch(null, PersonMatchKind.AMBIGUOUS)
        return PersonMatch(null, PersonMatchKind.NONE)
    }

    /** Decision 32: remembering an alias, once, for a person. */
    fun withAlias(aliases: List<String>, alias: String): List<String> {
        val list = aliases.toMutableList()
        val trimmed = alias.trim().replace(WHITESPACE, " ")
        if (trimmed.isNotEmpty() && list.none { normalizeName(it) == normalizeName(trimmed) }) list.add(trimmed)
        return list
    }

    // --- Counts and pattern (decision 9) ---------------------------------------------------

    /**
     * Counts for one person over the last 90 local days ([today] included): closed promises of theirs
     * by their final verdict's day, renegotiations and misses by the verdict's day, and what is open
     * now.
     */
    fun personCounts(
        personId: Long,
        ledger: ProLedger?,
        isOpenTask: (Long) -> Boolean,
        today: LocalDate,
        zone: TimeZone,
    ): PersonCounts {
        var delivered = 0
        var late = 0
        var dropped = 0
        var takenOver = 0
        var renegotiated = 0
        var missed = 0
        var openTheirs = 0
        var openMine = 0
        fun inWindow(at: String?): Boolean {
            val day = localDayOf(at, zone) ?: return false
            val days = day.daysUntil(today)
            return days >= 0 && days < PERSON_WINDOW_DAYS
        }
        for ((id, promise) in ledger?.values("promises").orEmpty()) {
            if (promise.whole("person") != personId) continue
            val final = LedgerRules.finalVerdictOf(promise)
            val taskId = id.toLongOrNull()
            if (final == null && taskId != null && isOpenTask(taskId)) {
                when (promise.text("dir")) {
                    "theirs" -> openTheirs++
                    "mine" -> openMine++
                }
            }
            if (promise.text("dir") != "theirs") continue
            for (verdict in LedgerRules.activeVerdicts(promise)) {
                if (!inWindow(verdict.text("at"))) continue
                when (verdict.text("kind")) {
                    "renegotiated" -> renegotiated++
                    "not_yet" -> missed++
                }
            }
            if (final != null && inWindow(final.text("at"))) {
                when (final.text("kind")) {
                    "delivered" -> delivered++
                    "late" -> late++
                    "dropped" -> dropped++
                    "taken_over" -> takenOver++
                }
            }
        }
        return PersonCounts(delivered, late, dropped, takenOver, renegotiated, missed, openTheirs, openMine)
    }

    private fun times(n: Int): String = when (n) {
        1 -> "once"
        2 -> "twice"
        else -> "$n times"
    }

    /**
     * The pattern in plain words. First sentence: what was kept, with late, dropped and taken over;
     * second: renegotiations and missed dates, when there are any.
     */
    fun patternText(counts: PersonCounts): String {
        val closed = counts.delivered + counts.late + counts.dropped + counts.takenOver
        val sentences = mutableListOf<String>()
        if (closed == 0) {
            sentences.add("No promises closed in the last 90 days.")
        } else {
            val extra = mutableListOf<String>()
            if (counts.late != 0) extra.add("${counts.late} late")
            if (counts.dropped != 0) extra.add("${counts.dropped} dropped")
            if (counts.takenOver != 0) extra.add("${counts.takenOver} done by you")
            sentences.add("Kept ${counts.delivered} of $closed on time in the last 90 days${extra.joinToString("") { ", $it" }}.")
        }
        val changes = mutableListOf<String>()
        if (counts.renegotiated != 0) changes.add("asked for a new date ${times(counts.renegotiated)}")
        if (counts.missed != 0) changes.add("missed a date ${times(counts.missed)}")
        if (changes.isNotEmpty()) {
            val text = changes.joinToString(" and ")
            sentences.add("${text.replaceFirstChar { it.uppercase() }}.")
        }
        return sentences.joinToString(" ")
    }
}
