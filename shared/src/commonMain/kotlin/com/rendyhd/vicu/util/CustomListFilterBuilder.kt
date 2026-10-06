package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.CustomListFilter
import com.rendyhd.vicu.domain.model.Task
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus

/**
 * The one custom-list evaluator (docs/cross-app-semantics-v1.md, sections 2 and 3). Desktop runs
 * the same vectors (`test-fixtures/cross-app-semantics-v1.json`) against its own evaluator.
 *
 * - Dates are local calendar dates: a due date is compared by the date it falls on in the zone,
 *   never by its instant or its UTC date. Weeks start on Monday ([DueDates.endOfWeek]).
 * - "Today" and the zone are passed in (from [DayClock]); nothing here reads the system clock.
 * - The server filter is only a superset of the exact window, built from the same local-day
 *   boundaries. The exact rule is always [matches], applied client-side.
 * - Every condition is evaluated on the full task set. A caller hides nested subtasks after
 *   filtering, so a matching subtask shows even when its parent does not.
 */
object CustomListFilterBuilder {

    /** The windows the `include_overdue` flag applies to. */
    private val WINDOWS_WITH_OVERDUE = setOf("today", "this_week", "this_month")

    /** Whether the "include overdue" option has any effect for [window] (the editor shows it then). */
    fun windowHonorsIncludeOverdue(window: String): Boolean = window in WINDOWS_WITH_OVERDUE

    // --- Evaluator ------------------------------------------------------------------------

    /**
     * Whether a task due on local date [due] (null when it has none) falls in [window] on
     * [today]. [includeOverdue] only matters for the today, this week and this month windows.
     * A window this version does not know (from a newer app) is no date condition.
     */
    fun inDateWindow(due: LocalDate?, window: String, includeOverdue: Boolean, today: LocalDate): Boolean =
        when (window) {
            "overdue" -> due != null && due < today
            "today" -> due != null && (due == today || (includeOverdue && due < today))
            "this_week" -> due != null &&
                ((due >= today && due <= DueDates.endOfWeek(today)) || (includeOverdue && due < today))
            "this_month" -> due != null &&
                ((due >= today && due <= DueDates.endOfMonth(today)) || (includeOverdue && due < today))
            "has_due_date" -> due != null
            "no_due_date" -> due == null
            else -> true // "all", or a window from a newer app
        }

    /** Whether [task] belongs on the list described by [filter] on the local date [today]. */
    fun matches(task: Task, filter: CustomListFilter, today: LocalDate, zone: TimeZone): Boolean {
        if (!filter.includeDone && task.done) return false

        val includeOverdue = filter.includesOverdue
        val due = DueDates.localDateOf(task.dueDate, zone)
        if (!inDateWindow(due, filter.dueDateFilter, includeOverdue, today)) return false

        if (filter.projectIds.isNotEmpty()) {
            val listed = task.projectId in filter.projectIds
            val projectOk = if (filter.projectFilterMode == "exclude") !listed else listed
            // "Today from all projects" brings back anything in the today window, whatever its project.
            if (!projectOk && !(filter.includeTodayAllProjects && inDateWindow(due, "today", includeOverdue, today))) {
                return false
            }
        }

        if (filter.priorityFilter.isNotEmpty() && task.priority !in filter.priorityFilter) return false

        if (filter.labelIds.isNotEmpty() && task.labels.none { it.id in filter.labelIds }) return false

        return true
    }

    /**
     * The tasks that match, in their original order. Call it on the full task set, before nested
     * subtasks are hidden, so a subtask that matches is kept even when its parent does not.
     */
    fun applyClientSideFilters(
        tasks: List<Task>,
        filter: CustomListFilter,
        today: LocalDate,
        zone: TimeZone,
    ): List<Task> = tasks.filter { matches(it, filter, today, zone) }

    /**
     * The rows a custom list shows, from the full task set [tasks]: the list's conditions first,
     * then only tasks of projects that still exist ([activeProjectIds]), then nested subtasks
     * hidden among the matches, then the configured sort. The list screen and the home screen
     * widget both call this, so they always show the same tasks.
     */
    fun visibleTasks(
        tasks: List<Task>,
        filter: CustomListFilter,
        today: LocalDate,
        zone: TimeZone,
        activeProjectIds: Set<Long>,
    ): List<Task> = sortTasks(
        applyClientSideFilters(tasks, filter, today, zone)
            .filter { it.projectId in activeProjectIds }
            .withoutNestedSubtasks(hideChildrenOfCompletedParents = false),
        filter.sortBy,
        filter.orderBy,
    )

    // --- Server filter --------------------------------------------------------------------

    /**
     * The clause for a date window, a superset of [inDateWindow] built from local-day boundaries
     * as UTC instants: `due_date < '<start of the day after the window's last day>'`. Null when
     * the window has no date condition.
     */
    private fun dateWindowClause(window: String, includeOverdue: Boolean, today: LocalDate, zone: TimeZone): String? {
        val notNull = "due_date != '${Constants.NULL_DATE_STRING}'"
        fun before(lastDay: LocalDate) = DueDates.startOfDay(lastDay.plus(1, DateTimeUnit.DAY), zone)
        fun bounded(lastDay: LocalDate) =
            if (includeOverdue) {
                "due_date < '${before(lastDay)}' && $notNull"
            } else {
                "due_date >= '${DueDates.startOfDay(today, zone)}' && due_date < '${before(lastDay)}'"
            }

        return when (window) {
            "overdue" -> "due_date < '${DueDates.startOfDay(today, zone)}' && $notNull"
            "today" -> bounded(today)
            "this_week" -> bounded(DueDates.endOfWeek(today))
            "this_month" -> bounded(DueDates.endOfMonth(today))
            "has_due_date" -> notNull
            "no_due_date" -> "due_date = '${Constants.NULL_DATE_STRING}'"
            else -> null
        }
    }

    /**
     * Builds a Vikunja API filter string for [filter] on [today] in [zone]. It never excludes a
     * task [matches] would accept: a project clause is only sent for include mode (and widened
     * with the today window for "today from all projects"), and priority and labels are left to
     * the client. The server may send more than the list shows; [applyClientSideFilters] decides.
     */
    fun buildFilterString(filter: CustomListFilter, today: LocalDate, zone: TimeZone): String {
        val parts = mutableListOf<String>()
        if (!filter.includeDone) parts.add("done = false")

        val includeOverdue = filter.includesOverdue
        if (filter.projectIds.isNotEmpty() && filter.projectFilterMode != "exclude") {
            val projectClause = if (filter.projectIds.size == 1) {
                "project_id = ${filter.projectIds.first()}"
            } else {
                filter.projectIds.joinToString(" || ", "(", ")") { "project_id = $it" }
            }
            if (filter.includeTodayAllProjects) {
                val todayClause = dateWindowClause("today", includeOverdue, today, zone)
                parts.add("($projectClause || ($todayClause))")
            } else {
                parts.add(projectClause)
            }
        }

        dateWindowClause(filter.dueDateFilter, includeOverdue, today, zone)?.let { parts.add(it) }

        return parts.joinToString(" && ")
    }

    /** Builds the query parameters for the Vikunja API (see [buildFilterString]). */
    fun buildQueryParams(filter: CustomListFilter, today: LocalDate, zone: TimeZone): Map<String, String> = buildMap {
        val filterStr = buildFilterString(filter, today, zone)
        if (filterStr.isNotBlank()) {
            put("filter", filterStr)
        }
        put("sort_by", filter.sortBy)
        put("order_by", filter.orderBy)
    }

    /** The sort keys [sortTasks] understands, in the order the editor offers them. */
    val SORT_KEYS: List<String> = listOf("due_date", "created", "updated", "priority", "title", "done_at", "position")

    /**
     * Orders tasks by a date field as instants (not as strings: "...:00.500Z" sorts before
     * "...:00Z" as text). A task without that date (blank, the null sentinel, or unreadable)
     * sorts last whichever way the list is ordered, so tasks without a due date never lead a
     * descending list.
     */
    private fun byDate(descending: Boolean, date: (Task) -> String): Comparator<Task> {
        val order: Comparator<Instant> = if (descending) reverseOrder() else naturalOrder()
        val datedFirst = nullsLast(order)
        return Comparator { a, b ->
            datedFirst.compare(DateUtils.parseIsoDate(date(a)), DateUtils.parseIsoDate(date(b)))
        }
    }

    private fun <T : Comparable<T>> byKey(descending: Boolean, key: (Task) -> T): Comparator<Task> =
        if (descending) compareByDescending(key) else compareBy(key)

    /**
     * Applies the custom list's configured sort client-side. The displayed list comes from
     * Room (not the API response), so the API-side sort_by/order_by alone has no effect on
     * what the user sees — this is the authoritative ordering.
     *
     * Tasks that tie keep their order, in both directions. For the date fields (due date,
     * created, updated, done at) tasks without the date come last in both directions.
     */
    fun sortTasks(tasks: List<Task>, sortBy: String, orderBy: String): List<Task> {
        val descending = orderBy.equals("desc", ignoreCase = true)
        val comparator: Comparator<Task> = when (sortBy) {
            "due_date" -> byDate(descending) { it.dueDate }
            "created" -> byDate(descending) { it.created }
            "updated" -> byDate(descending) { it.updated }
            "priority" -> byKey(descending) { it.priority }
            "title" -> byKey(descending) { it.title.lowercase() }
            "done_at" -> byDate(descending) { it.doneAt }
            "position" -> byKey(descending) { it.position }
            else -> byDate(descending = true) { it.updated }
        }
        return tasks.sortedWith(comparator)
    }
}
