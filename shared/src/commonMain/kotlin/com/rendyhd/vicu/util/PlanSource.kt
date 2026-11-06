package com.rendyhd.vicu.util

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus

/**
 * Which list the Plan screen's drawer shows, the same as the desktop's `lib/plan-source.ts`. [key] is
 * what a device stores (`day`, `inbox`, `next7`, `nodate`, `project:<id>`).
 */
sealed class PlanSource(val key: String) {
    data object Day : PlanSource("day")
    data object Inbox : PlanSource("inbox")
    data object NextSeven : PlanSource("next7")
    data object NoDate : PlanSource("nodate")
    data class Project(val id: Long) : PlanSource("project:$id")

    /** The project this source lists: its own, or the Inbox's for the Inbox. Else null. */
    fun projectId(inboxId: Long?): Long? = when (this) {
        is Project -> id
        Inbox -> inboxId?.takeIf { it > 0 }
        else -> null
    }

    companion object {
        private val projectKey = Regex("""^project:([1-9]\d*)$""")

        /** A stored value this version cannot read goes back to the first list. */
        fun read(value: String?): PlanSource = when (value) {
            Inbox.key -> Inbox
            NextSeven.key -> NextSeven
            NoDate.key -> NoDate
            else -> value
                ?.let { projectKey.matchEntire(it) }
                ?.let { it.groupValues[1].toLongOrNull() }
                ?.let { Project(it) }
                ?: Day
        }

        /** The four lists above the projects; [dayLabel] names the first (see [dayListLabel]). */
        fun lists(dayLabel: String): List<Pair<PlanSource, String>> = listOf(
            Day to dayLabel,
            Inbox to "Inbox",
            NextSeven to "Next 7 days",
            NoDate to "No date",
        )

        /** "Today, no time", "Tomorrow, no time", "Fri 6 Nov, no time". */
        fun dayListLabel(dayPhrase: String): String = "$dayPhrase, no time"

        /** Due after today and at most seven days on. */
        fun inNextSevenDays(due: String?, today: LocalDate, zone: TimeZone): Boolean {
            val day = DueDates.localDateOf(due, zone) ?: return false
            return day > today && day <= today.plus(7, DateTimeUnit.DAY)
        }

        /** The filter box: words of the title, any case. */
        fun matchesTitleFilter(title: String, filter: String): Boolean {
            val needle = filter.trim().lowercase()
            return needle.isEmpty() || title.lowercase().contains(needle)
        }
    }
}
