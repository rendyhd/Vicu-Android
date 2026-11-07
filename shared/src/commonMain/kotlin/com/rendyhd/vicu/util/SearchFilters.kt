package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.CustomListFilter
import com.rendyhd.vicu.domain.model.Task
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus

/**
 * The filters of a search (`docs/cross-app-semantics-v1.md`, section 17). They last for one search:
 * nothing here is saved or synced. The date windows are the custom lists' (3.1) without overdue tasks.
 */
data class SearchFilters(
    val date: String = DATE_ALL,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val status: String = STATUS_ALL,
    val labelIds: Set<Long> = emptySet(),
    val priorities: Set<Int> = emptySet(),
) {
    val isActive: Boolean
        get() = date != DATE_ALL || status != STATUS_ALL || labelIds.isNotEmpty() || priorities.isNotEmpty()

    /** The range's days in order, whichever way round they were picked. */
    private val range: ClosedRange<LocalDate>?
        get() {
            val first = from ?: return null
            val last = to ?: return null
            return if (first <= last) first..last else last..first
        }

    fun matches(task: Task, today: LocalDate, zone: TimeZone): Boolean {
        when (status) {
            STATUS_OPEN -> if (task.done) return false
            STATUS_DONE -> if (!task.done) return false
        }
        val due = DueDates.localDateOf(task.dueDate, zone)
        val inDate = if (date == DATE_RANGE) {
            due != null && range?.contains(due) == true
        } else {
            CustomListFilterBuilder.inDateWindow(due, date, includeOverdue = false, today = today)
        }
        if (!inDate) return false
        if (labelIds.isNotEmpty() && task.labels.none { it.id in labelIds }) return false
        return priorities.isEmpty() || task.priority in priorities
    }

    /**
     * What to ask the server: a superset of what [matches] accepts. With no [text] to narrow the read,
     * labels and priority go along, so it does not read every task there is.
     */
    fun serverParams(text: String, today: LocalDate, zone: TimeZone): Map<String, String> {
        val parts = mutableListOf<String>()
        when (status) {
            STATUS_OPEN -> parts += "done = false"
            STATUS_DONE -> parts += "done = true"
        }
        val days = range
        if (date == DATE_RANGE && days != null) {
            parts += "due_date >= '${DueDates.startOfDay(days.start, zone)}'"
            parts += "due_date < '${DueDates.startOfDay(days.endInclusive.plus(1, DateTimeUnit.DAY), zone)}'"
        } else {
            val window = CustomListFilter(dueDateFilter = date, includeOverdue = false, includeDone = true)
            CustomListFilterBuilder.buildFilterString(window, today, zone).takeIf { it.isNotBlank() }?.let { parts += it }
        }
        if (text.isEmpty()) {
            if (labelIds.isNotEmpty()) parts += "labels in ${labelIds.sorted().joinToString(",")}"
            if (priorities.isNotEmpty()) parts += "priority in ${priorities.sorted().joinToString(",")}"
        }
        return buildMap {
            if (text.isNotEmpty()) put("q", text)
            if (parts.isNotEmpty()) put("filter", parts.joinToString(" && "))
        }
    }

    companion object {
        const val DATE_ALL = "all"
        const val DATE_RANGE = "range"
        const val STATUS_ALL = "all"
        const val STATUS_OPEN = "open"
        const val STATUS_DONE = "done"
    }
}
