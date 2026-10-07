package com.rendyhd.vicu.domain.repository

import com.rendyhd.vicu.domain.model.CustomListFilter
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.CustomListFilterBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * The one source a custom list reads, for the list screen and for the home screen widget: every
 * task, nested subtasks included (the list's conditions apply to all of them and nested ones are
 * hidden afterwards, among the matches). Done tasks are part of it only when the list shows them
 * (`include_done`).
 */
fun TaskRepository.customListSource(filter: CustomListFilter): Flow<List<Task>> =
    if (filter.includeDone) getAllTasksFlat() else getAllOpenTasksFlat()

/**
 * The tasks a custom list shows right now, read once: [customListSource] through the one
 * evaluator ([CustomListFilterBuilder.visibleTasks]), the same as the list screen. The widget
 * uses this; it must not read a capped sample of tasks and filter that.
 */
suspend fun TaskRepository.customListTasks(
    filter: CustomListFilter,
    today: LocalDate,
    zone: TimeZone,
    activeProjectIds: Set<Long>,
): List<Task> =
    CustomListFilterBuilder.visibleTasks(customListSource(filter).first(), filter, today, zone, activeProjectIds)
