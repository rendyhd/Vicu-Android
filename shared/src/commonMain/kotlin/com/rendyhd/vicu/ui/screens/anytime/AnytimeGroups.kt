package com.rendyhd.vicu.ui.screens.anytime

import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.components.section.ProjectMeta
import com.rendyhd.vicu.ui.components.section.showsGroupHeader
import com.rendyhd.vicu.ui.screens.project.ProjectSection
import com.rendyhd.vicu.ui.screens.project.openTaskCount
import com.rendyhd.vicu.ui.screens.project.totalTaskCount

/**
 * One top-level project of the Anytime screen: its own open tasks, then every sub-project (at any
 * depth) that has open tasks somewhere below it.
 */
data class AnytimeProjectGroup(
    val project: Project,
    val unsectionedTasks: List<Task>,
    val sections: List<ProjectSection>,
    val isExpanded: Boolean = true,
)

/** One line of the Anytime list: a project header or a task, with its indentation level. */
sealed interface AnytimeRow {
    /** Stable lazy-list key: a header and a task can never share one. */
    val key: String

    val depth: Int

    data class Header(
        val project: Project,
        override val depth: Int,
        val isExpanded: Boolean,
        /** Open tasks in the project and everything below it. */
        val taskCount: Int,
    ) : AnytimeRow {
        override val key: String get() = "header_${project.id}"
    }

    /** [projectMeta] is set when the task's group has no header (a group of one task). */
    data class TaskRow(val task: Task, override val depth: Int, val projectMeta: ProjectMeta? = null) : AnytimeRow {
        override val key: String get() = "task_${task.id}"
    }
}

/**
 * Groups [tasks] by the project tree. A project is a root when it has no parent or its parent is
 * not in [projects]; the rest hang below their parent however deep they are. Branches without any
 * task are left out, siblings are ordered by title, and a project is collapsed when its id is in
 * [collapsedProjectIds]. Cyclic parent data neither loops nor drops tasks: the members of a cycle
 * are shown as roots.
 */
fun buildAnytimeGroups(
    projects: List<Project>,
    tasks: List<Task>,
    collapsedProjectIds: Set<Long>,
): List<AnytimeProjectGroup> {
    val tasksByProject = tasks.groupBy { it.projectId }
    val known = projects.mapTo(HashSet()) { it.id }
    val childrenByParent = projects
        .filter { it.parentProjectId in known }
        .groupBy { it.parentProjectId }
    val visited = HashSet<Long>()

    fun section(project: Project): ProjectSection? {
        if (!visited.add(project.id)) return null
        val children = childrenByParent[project.id].orEmpty()
            .sortedBy { it.title.lowercase() }
            .mapNotNull { section(it) }
        val own = tasksByProject[project.id].orEmpty()
        if (own.isEmpty() && children.isEmpty()) return null
        return ProjectSection(
            project = project,
            tasks = own,
            children = children,
            isExpanded = project.id !in collapsedProjectIds,
        )
    }

    fun group(project: Project): AnytimeProjectGroup? =
        section(project)?.let {
            AnytimeProjectGroup(
                project = it.project,
                unsectionedTasks = it.tasks,
                sections = it.children,
                isExpanded = it.isExpanded,
            )
        }

    val roots = projects
        .filter { it.parentProjectId !in known || it.parentProjectId == it.id }
        .sortedBy { it.title.lowercase() }
    val groups = roots.mapNotNull { group(it) }.toMutableList()
    // Only a cycle is unreachable from a root: its members would otherwise lose their tasks.
    projects
        .filter { it.id !in visited }
        .sortedBy { it.title.lowercase() }
        .mapNotNullTo(groups) { group(it) }
    return groups.sortedBy { it.project.title.lowercase() }
}

/**
 * The lazy list's rows for [groups], depth first; the children of a collapsed project are skipped.
 * A project holding a single task (counting finished ones) gets no header: that task carries the
 * project on its row instead. [completedIds] are rows kept on screen after completing them; they
 * do not count as open.
 */
fun flattenAnytimeGroups(groups: List<AnytimeProjectGroup>, completedIds: Set<Long> = emptySet()): List<AnytimeRow> {
    val rows = ArrayList<AnytimeRow>()

    fun addSection(section: ProjectSection, depth: Int) {
        val hasHeader = showsGroupHeader(totalTaskCount(section))
        if (hasHeader) {
            rows += AnytimeRow.Header(section.project, depth, section.isExpanded, openTaskCount(section, completedIds))
            if (!section.isExpanded) return
        }
        val meta = if (hasHeader) null else ProjectMeta(section.project.title, section.project.hexColor)
        section.tasks.forEach { rows += AnytimeRow.TaskRow(it, depth, meta) }
        section.children.forEach { addSection(it, depth + 1) }
    }

    groups.forEach { group ->
        addSection(
            ProjectSection(group.project, group.unsectionedTasks, group.sections, group.isExpanded),
            depth = 0,
        )
    }
    return rows
}
