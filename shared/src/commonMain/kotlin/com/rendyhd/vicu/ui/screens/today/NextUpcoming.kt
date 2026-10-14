package com.rendyhd.vicu.ui.screens.today

import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.screens.upcoming.buildUpcomingDays
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * What an emptied Today offers (card 4.11b): the next open task that is due after [today] (local
 * day, tomorrow or later), the first one of the first day that has any, in the order the Upcoming
 * list gives it. Null when nothing is upcoming. It reads the same rows as Upcoming, so the two agree.
 */
fun nextUpcomingTask(tasks: List<Task>, projects: List<Project>, today: LocalDate, zone: TimeZone): Task? =
    buildUpcomingDays(tasks, projects, today, zone).firstOrNull()?.tasks?.firstOrNull()
