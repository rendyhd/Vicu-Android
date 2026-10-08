package com.rendyhd.vicu.ui.screens.upcoming

import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.DueDates
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil

/** One local calendar day of the Upcoming list: its heading and its tasks in order. */
data class UpcomingDay(
    val date: LocalDate,
    val label: String,
    val tasks: List<Task>,
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
            UpcomingDay(
                date = date,
                label = upcomingDayLabel(date, today),
                tasks = rows
                    .sortedWith(compareBy({ it.first }, { it.second.position }, { it.second.id }))
                    .map { it.second },
            )
        }
}

private val WEEKDAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

private val MONTHS = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

private fun LocalDate.weekdayName(): String = WEEKDAYS[dayOfWeek.ordinal]

/**
 * The heading of a day: "Tomorrow", the weekday name for the rest of the week ahead (2 to 6 days
 * out), and the weekday with the month and day further on, as on the desktop.
 *
 * Temporary: the shared header formatter planned for card 2.1b replaces this.
 */
fun upcomingDayLabel(date: LocalDate, today: LocalDate): String {
    val ahead = today.daysUntil(date)
    return when {
        ahead == 1 -> "Tomorrow"
        ahead in 2..6 -> date.weekdayName()
        else -> "${date.weekdayName()}, ${MONTHS[date.monthNumber - 1]} ${date.dayOfMonth}"
    }
}
