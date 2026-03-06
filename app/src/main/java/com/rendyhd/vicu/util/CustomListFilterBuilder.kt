package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.CustomListFilter
import com.rendyhd.vicu.domain.model.Task
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object CustomListFilterBuilder {

    private val isoFormatter: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

    /**
     * Builds a Vikunja API filter string from a CustomListFilter.
     * Only the first project_id is sent to the API (multi-project is client-side).
     */
    fun buildFilterString(filter: CustomListFilter): String {
        val parts = mutableListOf<String>()

        // Done filter
        if (!filter.includeDone) {
            parts.add("done = false")
        }

        // Single project filter (API only supports one; exclude mode uses client-side only)
        if (filter.projectFilterMode != "exclude" && filter.projectIds.size == 1) {
            parts.add("project_id = ${filter.projectIds.first()}")
        }

        // Due date filter
        val now = LocalDate.now(ZoneOffset.UTC)
        val nullDate = Constants.NULL_DATE_STRING
        when (filter.dueDateFilter) {
            "overdue" -> {
                val startOfToday = now.atStartOfDay().toInstant(ZoneOffset.UTC)
                parts.add("due_date < '${isoFormatter.format(startOfToday)}'")
                parts.add("due_date != '$nullDate'")
            }
            "today" -> {
                val endOfToday = now.plusDays(1).atStartOfDay().minusNanos(1).toInstant(ZoneOffset.UTC)
                parts.add("due_date <= '${isoFormatter.format(endOfToday)}'")
                parts.add("due_date != '$nullDate'")
            }
            "this_week" -> {
                val endOfWeek = now.plusWeeks(1).atStartOfDay().minusNanos(1).toInstant(ZoneOffset.UTC)
                parts.add("due_date <= '${isoFormatter.format(endOfWeek)}'")
                parts.add("due_date != '$nullDate'")
            }
            "this_month" -> {
                val endOfMonth = now.plusMonths(1).atStartOfDay().minusNanos(1).toInstant(ZoneOffset.UTC)
                parts.add("due_date <= '${isoFormatter.format(endOfMonth)}'")
                parts.add("due_date != '$nullDate'")
            }
            "has_due_date" -> {
                parts.add("due_date != '$nullDate'")
                parts.add("due_date != ''")
            }
            "no_due_date" -> {
                parts.add("(due_date = '$nullDate' || due_date = '')")
            }
            // "all" -> no due date filter
        }

        // Include today from all projects (union mode) — only for include mode
        if (filter.projectFilterMode != "exclude" && filter.includeTodayAllProjects && filter.projectIds.isNotEmpty()) {
            val endOfToday = now.plusDays(1).atStartOfDay().minusNanos(1).toInstant(ZoneOffset.UTC)
            val startOfToday = now.atStartOfDay().toInstant(ZoneOffset.UTC)
            val projectFilter = if (filter.projectIds.size == 1) {
                "project_id = ${filter.projectIds.first()}"
            } else {
                // Multi-project handled client-side, use first project for API
                "project_id = ${filter.projectIds.first()}"
            }
            val doneFilter = if (!filter.includeDone) "done = false && " else ""
            return "${doneFilter}(($projectFilter) || (due_date >= '${isoFormatter.format(startOfToday)}' && due_date <= '${isoFormatter.format(endOfToday)}' && due_date != '$nullDate'))"
        }

        return parts.joinToString(" && ")
    }

    /**
     * Builds query parameters for the Vikunja API.
     */
    fun buildQueryParams(filter: CustomListFilter): Map<String, String> = buildMap {
        val filterStr = buildFilterString(filter)
        if (filterStr.isNotBlank()) {
            put("filter", filterStr)
        }
        put("sort_by", filter.sortBy)
        put("order_by", filter.orderBy)
    }

    /**
     * Applies client-side filters that can't be handled by the API.
     * Call this on the task list returned from the API/Room.
     */
    fun applyClientSideFilters(tasks: List<Task>, filter: CustomListFilter): List<Task> {
        var result = tasks

        // Project filter (handles include and exclude modes)
        if (filter.projectIds.isNotEmpty()) {
            if (filter.projectFilterMode == "exclude") {
                // Exclude mode: remove tasks from the selected projects
                if (filter.includeTodayAllProjects) {
                    result = result.filter { task ->
                        task.projectId !in filter.projectIds || isTaskDueToday(task.dueDate)
                    }
                } else {
                    result = result.filter { it.projectId !in filter.projectIds }
                }
            } else {
                // Include mode (default)
                if (filter.includeTodayAllProjects) {
                    result = result.filter { task ->
                        task.projectId in filter.projectIds || isTaskDueToday(task.dueDate)
                    }
                } else {
                    result = result.filter { it.projectId in filter.projectIds }
                }
            }
        }

        // Due date filter
        val now = LocalDate.now()
        val localZone = ZoneId.systemDefault()
        when (filter.dueDateFilter) {
            "overdue" -> {
                val startOfToday = now.atStartOfDay(localZone).toInstant()
                result = result.filter { task ->
                    val instant = DateUtils.parseIsoDate(task.dueDate)
                    instant != null && instant.isBefore(startOfToday)
                }
            }
            "today" -> {
                val endOfToday = now.plusDays(1).atStartOfDay(localZone).toInstant()
                result = result.filter { task ->
                    val instant = DateUtils.parseIsoDate(task.dueDate)
                    instant != null && instant.isBefore(endOfToday)
                }
            }
            "this_week" -> {
                val endOfWeek = now.plusWeeks(1).atStartOfDay(localZone).toInstant()
                result = result.filter { task ->
                    val instant = DateUtils.parseIsoDate(task.dueDate)
                    instant != null && instant.isBefore(endOfWeek)
                }
            }
            "this_month" -> {
                val endOfMonth = now.plusMonths(1).atStartOfDay(localZone).toInstant()
                result = result.filter { task ->
                    val instant = DateUtils.parseIsoDate(task.dueDate)
                    instant != null && instant.isBefore(endOfMonth)
                }
            }
            "has_due_date" -> {
                result = result.filter { !DateUtils.isNullDate(it.dueDate) }
            }
            "no_due_date" -> {
                result = result.filter { DateUtils.isNullDate(it.dueDate) }
            }
            // "all" -> no due date filter
        }

        // Priority filter
        if (filter.priorityFilter.isNotEmpty()) {
            result = result.filter { it.priority in filter.priorityFilter }
        }

        // Label filter (task must have at least one of the specified labels)
        if (filter.labelIds.isNotEmpty()) {
            result = result.filter { task ->
                task.labels.any { it.id in filter.labelIds }
            }
        }

        return result
    }

    private fun isTaskDueToday(dueDate: String): Boolean {
        val instant = DateUtils.parseIsoDate(dueDate) ?: return false
        val now = LocalDate.now()
        val localZone = ZoneId.systemDefault()
        val startOfToday = now.atStartOfDay(localZone).toInstant()
        val startOfTomorrow = now.plusDays(1).atStartOfDay(localZone).toInstant()
        return !instant.isBefore(startOfToday) && instant.isBefore(startOfTomorrow)
    }
}
