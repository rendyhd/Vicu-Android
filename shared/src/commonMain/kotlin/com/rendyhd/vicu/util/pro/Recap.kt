package com.rendyhd.vicu.util.pro

import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.DateDisplayFormat
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

enum class RecapSection(val key: String, val title: String) {
    DELIVERED("delivered", "Delivered"),
    AGREED("agreed", "Agreed"),
    CHANGED("changed", "Changed"),
    OPEN("open", "Open"),
    MINE("mine", "I owe"),
}

data class RecapScope(
    /** The local days the recap covers, both included (one day for a meeting). */
    val from: LocalDate,
    val to: LocalDate,
    /** The people in the recap, in the order they are shown. */
    val people: List<Long>,
    val includeMine: Boolean,
)

data class RecapItem(
    val section: RecapSection,
    val taskId: Long,
    val person: Long,
    val title: String,
    /** The effective due day, or null. */
    val due: LocalDate?,
    val late: Boolean = false,
    val newDue: LocalDate? = null,
    val blocker: String? = null,
    val words: String? = null,
)

class Recap(
    val text: String,
    val html: String,
    /** The promises copied, to mark as sent. */
    val taskIds: List<Long>,
)

/**
 * The recap (docs/cross-app-semantics-v1.md section 21.8), the same as the desktop's
 * `src/shared/pro/recap.ts`: what was delivered, agreed, changed and is still open, per person, plus
 * what you owe and who was not discussed. One copy holds both a plain text and an HTML version
 * (decision 13); copying it marks the promises in it as sent (decision 7).
 */
object Recaps {
    private val SECTION_ORDER = RecapSection.entries

    private fun inRange(day: LocalDate?, scope: RecapScope): Boolean = day != null && day >= scope.from && day <= scope.to

    /**
     * The items of a recap: for each person in scope, their `theirs` promises delivered in the range
     * (delivered or late), agreed in it (the task was created in it), changed in it (the last verdict,
     * not yet or renegotiated, was given in it) and the rest still open; with `includeMine`, your open
     * `mine` promises to them. A promise is in one section only, the first that applies.
     */
    fun items(scope: RecapScope, tasks: List<Task>, ledger: ProLedger?, zone: TimeZone): List<RecapItem> {
        val items = mutableListOf<RecapItem>()
        val people = scope.people.toSet()
        for (task in tasks) {
            val promise = ledger?.value("promises", task.id) ?: continue
            val person = promise.whole("person") ?: continue
            if (person !in people) continue
            val due = Promises.effectiveDue(task, promise, zone)
            val words = promise.text("words")
            fun item(section: RecapSection, late: Boolean = false, newDue: LocalDate? = null, blocker: String? = null) =
                RecapItem(section, task.id, person, task.title, due, late, newDue, blocker, words)
            val final = LedgerRules.finalVerdictOf(promise)
            if (promise.text("dir") == "mine") {
                if (scope.includeMine && final == null && !task.done) items.add(item(RecapSection.MINE))
                continue
            }
            if (promise.text("dir") != "theirs") continue
            if (final != null) {
                val kind = final.text("kind")
                if ((kind == "delivered" || kind == "late") && inRange(localDayOf(final.text("at"), zone), scope)) {
                    items.add(item(RecapSection.DELIVERED, late = kind == "late"))
                }
                continue
            }
            if (task.done) continue
            val last = LedgerRules.activeVerdicts(promise).lastOrNull()
            val lastKind = last?.text("kind")
            if (task.created.isNotEmpty() && inRange(localDayOf(task.created, zone), scope) && last == null) {
                items.add(item(RecapSection.AGREED))
            } else if (last != null && (lastKind == "not_yet" || lastKind == "renegotiated") && inRange(localDayOf(last.text("at"), zone), scope)) {
                val newDue = last.text("new_due")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                items.add(item(RecapSection.CHANGED, newDue = newDue, blocker = last.text("blocker")))
            } else {
                items.add(item(RecapSection.OPEN))
            }
        }
        val order = scope.people.withIndex().associate { it.value to it.index }
        return items.sortedWith(
            Comparator { a, b ->
                val section = SECTION_ORDER.indexOf(a.section) - SECTION_ORDER.indexOf(b.section)
                if (section != 0) return@Comparator section
                val person = (order[a.person] ?: 0) - (order[b.person] ?: 0)
                if (person != 0) return@Comparator person
                val due = when {
                    a.due == b.due -> 0
                    a.due == null -> 1
                    b.due == null -> -1
                    a.due < b.due -> -1
                    else -> 1
                }
                if (due != 0) due else a.taskId.compareTo(b.taskId)
            },
        )
    }

    private fun escapeHtml(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private class Writer(val today: LocalDate, val fmt: DateDisplayFormat) {
        fun date(day: LocalDate): String = DateDisplay.formatWeekdayDate(day, today, fmt)
    }

    private fun itemLine(item: RecapItem, personName: (Long) -> String, writer: Writer): String {
        var line = "${personName(item.person)}: ${item.title.trim()}"
        if (item.section == RecapSection.DELIVERED) {
            if (item.late) line += " (late)"
        } else if (item.section == RecapSection.CHANGED) {
            line += if (item.newDue != null) ", new date ${writer.date(item.newDue)}" else ", not yet"
            if (!item.blocker.isNullOrEmpty()) line += " (${item.blocker})"
        } else if (item.due != null) {
            line += ", by ${writer.date(item.due)}"
        }
        return line
    }

    /**
     * The recap's two versions. Text: the title line, a blank line, then each section with items as
     * "- " lines (a quote on the next line, indented two spaces, when asked for), then "Not
     * discussed:" and "Absent:" lines. HTML: the same as paragraphs and lists, with the quotes in
     * `<em>`.
     */
    fun build(
        title: String,
        date: LocalDate,
        items: List<RecapItem>,
        personName: (Long) -> String,
        notDiscussed: List<String>,
        absent: List<String>,
        includeQuotes: Boolean,
        now: Instant,
        zone: TimeZone,
        fmt: DateDisplayFormat,
    ): Recap {
        val writer = Writer(now.toLocalDateTime(zone).date, fmt)
        val name = title.trim()
        val dateText = writer.date(date)
        val text = mutableListOf("$name, $dateText")
        val html = mutableListOf("<p><strong>${escapeHtml(name)}</strong>, ${escapeHtml(dateText)}</p>")
        var first = true
        for (section in SECTION_ORDER) {
            val inSection = items.filter { it.section == section }
            if (inSection.isEmpty()) continue
            if (first) text.add("")
            first = false
            text.add(section.title)
            html.add("<p><strong>${section.title}</strong></p>")
            html.add("<ul>")
            for (item in inSection) {
                val line = itemLine(item, personName, writer)
                val quote = if (includeQuotes) item.words?.trim()?.takeIf { it.isNotEmpty() } else null
                text.add("- $line")
                if (quote != null) text.add("  \"$quote\"")
                html.add("<li>${escapeHtml(line)}${if (quote != null) "<br><em>\"${escapeHtml(quote)}\"</em>" else ""}</li>")
            }
            html.add("</ul>")
        }
        for ((label, names) in listOf("Not discussed" to notDiscussed, "Absent" to absent)) {
            if (names.isEmpty()) continue
            if (first) text.add("")
            first = false
            text.add("$label: ${names.joinToString(", ")}")
            html.add("<p>$label: ${escapeHtml(names.joinToString(", "))}</p>")
        }
        return Recap(
            text = text.joinToString("\n"),
            html = html.joinToString(""),
            taskIds = items.map { it.taskId }.toSet().sorted(),
        )
    }
}
