package com.rendyhd.vicu.ui.screens.shared

import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task

/** A per-project group of tasks for the Today/Upcoming grouped views (its header is not collapsible). */
data class TaskProjectGroup(
    val projectId: Long,
    val title: String,
    val hexColor: String,
    val tasks: List<Task>,
)

/**
 * Groups due-date-filtered tasks by project (one section per project), with tasks
 * sorted by due date within each group. Inbox tasks fall into an "Inbox" group; groups are
 * ordered by title. Used by Today and Upcoming so they convey project the way Anytime does.
 */
fun buildTaskProjectGroups(
    tasks: List<Task>,
    projects: List<Project>,
    inboxId: Long?,
): List<TaskProjectGroup> {
    val projectsById = projects.associateBy { it.id }
    return tasks
        .filter { it.projectId in projectsById }
        .groupBy { it.projectId }
        .map { (projectId, groupTasks) ->
            val project = projectsById[projectId]
            val title = when {
                inboxId != null && projectId == inboxId -> "Inbox"
                project != null -> project.title
                else -> "No project"
            }
            TaskProjectGroup(
                projectId = projectId,
                title = title,
                hexColor = project?.hexColor.orEmpty(),
                tasks = groupTasks.sortedBy { it.dueDate },
            )
        }
        .sortedBy { it.title.lowercase() }
}
