package com.rendyhd.vicu.ui.screens.upcoming

import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.screens.shared.TaskProjectGroup
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.DueDates
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * One local calendar day of the Upcoming list and its tasks in order; the screen phrases the
 * heading. [groups] is the same tasks per project (by project name, like the desktop), each group
 * in the day's order.
 */
data class UpcomingDay(
    val date: LocalDate,
    val tasks: List<Task>,
    val groups: List<TaskProjectGroup> = emptyList(),
)

/**
 * Groups open tasks by the local day they are due, the way the desktop Upcoming view does.
 *
 * Only tasks due tomorrow or later (local) in a known project are kept, so the list follows
 * [today] even when the stored query has not caught up yet. Days come in date order and a day with
 * no task is not listed. Within a day tasks are ordered by due time, then by list position, then
 * by id so equal rows keep a stable order.
 */
fun buildUpcomingDays(
    tasks: List<Task>,
    projects: List<Project>,
    today: LocalDate,
    zone: TimeZone,
): List<UpcomingDay> {
    val projectIds = projects.mapTo(HashSet()) { it.id }
    return tasks
        .asSequence()
        .filter { it.projectId in projectIds }
        .mapNotNull { task ->
            val due = DateUtils.parseIsoDate(task.dueDate) ?: return@mapNotNull null
            if (DueDates.bucket(due, today, zone) != DueDates.Bucket.UPCOMING) return@mapNotNull null
            Triple(DueDates.localDateOf(due, zone), due, task)
        }
        .groupBy({ it.first }, { it.second to it.third })
        .toSortedMap()
        .map { (date, rows) ->
            val ordered = rows
                .sortedWith(compareBy({ it.first }, { it.second.position }, { it.second.id }))
                .map { it.second }
            UpcomingDay(date = date, tasks = ordered, groups = groupByProject(ordered, projects))
        }
}

/** The day's tasks per project, groups ordered by project name, tasks keeping the day's order. */
private fun groupByProject(tasks: List<Task>, projects: List<Project>): List<TaskProjectGroup> {
    val byId = projects.associateBy { it.id }
    return tasks
        .groupBy { it.projectId }
        .map { (projectId, group) ->
            val project = byId[projectId]
            TaskProjectGroup(
                projectId = projectId,
                title = project?.title ?: "No project",
                hexColor = project?.hexColor.orEmpty(),
                tasks = group,
            )
        }
        .sortedBy { it.title.lowercase() }
}
