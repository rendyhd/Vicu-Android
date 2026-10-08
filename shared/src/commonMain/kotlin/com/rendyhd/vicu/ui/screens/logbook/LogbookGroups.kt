package com.rendyhd.vicu.ui.screens.logbook

import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.DateContext
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.DateDisplayFormat
import com.rendyhd.vicu.util.DateUtils
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** A completed task with the time of day it was completed ("" for a task without a completion time). */
data class LogbookRowItem(val task: Task, val time: String)

/** One day of the Logbook: its heading ("Today", "Yesterday", "Mon 5 Oct", "September 2026") and its rows. */
data class LogbookGroup(val title: String, val rows: List<LogbookRowItem>)

/**
 * The tasks, newest first, split into day groups by completion time (`logbook.group`), each row with
 * its time of day (`logbook.time`); the same rule as the desktop `groupLogbookTasks`. A group is a
 * run of neighbours with the same heading. A task without a completion time (one reopened in the
 * Logbook) has no time and stays in the group it sits in; at the very top it forms a group with an
 * empty heading.
 */
fun groupLogbookTasks(
    tasks: List<Task>,
    today: LocalDate,
    zone: TimeZone,
    fmt: DateDisplayFormat,
): List<LogbookGroup> {
    val groups = mutableListOf<Pair<String, MutableList<LogbookRowItem>>>()
    for (task in tasks) {
        val completed = !DateUtils.isNullDate(task.doneAt)
        val title = if (completed) DateDisplay.formatDue(DateContext.LOGBOOK_GROUP, task.doneAt, today, zone, fmt) else ""
        val time = if (completed) DateDisplay.formatDue(DateContext.LOGBOOK_TIME, task.doneAt, today, zone, fmt) else ""
        val last = groups.lastOrNull()
        if (last != null && (last.first == title || !completed)) {
            last.second.add(LogbookRowItem(task, time))
        } else {
            groups.add(title to mutableListOf(LogbookRowItem(task, time)))
        }
    }
    return groups.map { (title, rows) -> LogbookGroup(title, rows) }
}
