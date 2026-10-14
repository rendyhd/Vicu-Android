package com.rendyhd.vicu.ui.components.section

import com.rendyhd.vicu.domain.model.Task

// Small rules of the grouped lists (Today, Upcoming, Anytime, Tag, Project): what a header counts and
// when a group is too small to deserve one. The same rules as the desktop `lib/list-sections.ts`.

/**
 * The tasks of a group that are still open: the number a header shows, so it drops as soon as one
 * is completed. [completedIds] are rows kept on screen after completing them (`CompletionHold`).
 */
fun openCount(tasks: List<Task>, completedIds: Set<Long> = emptySet()): Int =
    tasks.count { !it.done && it.id !in completedIds }

/**
 * A group of one task gets no header; its project goes on the row's meta line instead. [size] is
 * the number of tasks in the group, finished ones included, so completing a task never makes the
 * header of its neighbours appear or vanish under the finger.
 */
fun showsGroupHeader(size: Int): Boolean = size > 1

/** The project a row names on its meta line because its group has no header ([showsGroupHeader]). */
data class ProjectMeta(val title: String, val hexColor: String)
