package com.rendyhd.vicu.ui.screens.project

import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.moveTaskInList

/**
 * One project section: a descendant project rendered as a collapsible group, its undone
 * tasks, and its own nested sub-sections. [isExpanded] is local UI state restored from
 * [com.rendyhd.vicu.data.local.ProjectSectionPrefsStore].
 */
data class ProjectSection(
    val project: Project,
    val tasks: List<Task>,
    val children: List<ProjectSection> = emptyList(),
    val isExpanded: Boolean = true,
)

/**
 * Every descendant project of [rootId] at any depth, depth-first, siblings ordered by
 * position. A `visited` guard terminates on pre-existing cyclic parent data (A->B->A).
 */
fun collectDescendants(rootId: Long, projects: List<Project>): List<Project> {
    val childMap = projects.groupBy { it.parentProjectId }
    val result = mutableListOf<Project>()
    val visited = mutableSetOf<Long>()
    fun recurse(parentId: Long) {
        childMap[parentId]?.sortedBy { it.position }?.forEach { child ->
            if (!visited.add(child.id)) return@forEach
            result.add(child)
            recurse(child.id)
        }
    }
    recurse(rootId)
    return result
}

/** Direct child projects of [rootId], ordered by their position in Vikunja. */
fun directChildProjects(rootId: Long, projects: List<Project>): List<Project> =
    projects.filter { it.parentProjectId == rootId }.sortedBy { it.position }

/**
 * Nested section tree for the direct children of [rootId] (recursively). Children are ordered
 * by position; each node's tasks come from [tasksByProject] (already filtered to undone and
 * sorted by the caller), defaulting to empty.
 */
fun buildSectionTree(
    rootId: Long,
    projects: List<Project>,
    tasksByProject: Map<Long, List<Task>>,
): List<ProjectSection> {
    val childMap = projects.groupBy { it.parentProjectId }
    val visited = mutableSetOf<Long>()
    fun build(parentId: Long): List<ProjectSection> =
        childMap[parentId].orEmpty()
            .sortedBy { it.position }
            .mapNotNull { project ->
                if (!visited.add(project.id)) return@mapNotNull null
                ProjectSection(
                    project = project,
                    tasks = tasksByProject[project.id].orEmpty(),
                    children = build(project.id),
                )
            }
    return build(rootId)
}

/**
 * Recursively carry [old]'s isExpanded onto [new], matched by project id. A node [old] has not
 * seen keeps the state it was built with, which is the collapsed state restored from storage
 * ([restoreExpansion]); defaulting it to expanded would undo the restore the first time a
 * freshly created ViewModel publishes its sections.
 */
fun preserveExpansion(new: List<ProjectSection>, old: List<ProjectSection>): List<ProjectSection> {
    val expandedById = HashMap<Long, Boolean>()
    fun index(list: List<ProjectSection>) {
        list.forEach {
            expandedById[it.project.id] = it.isExpanded
            index(it.children)
        }
    }
    index(old)
    fun apply(list: List<ProjectSection>): List<ProjectSection> = list.map { s ->
        s.copy(
            isExpanded = expandedById[s.project.id] ?: s.isExpanded,
            children = apply(s.children),
        )
    }
    return apply(new)
}

/** Apply the collapsed ids restored for the current root project to a newly built tree. */
fun restoreExpansion(
    sections: List<ProjectSection>,
    collapsedSectionIds: Set<Long>,
): List<ProjectSection> = sections.map { section ->
    section.copy(
        isExpanded = section.project.id !in collapsedSectionIds,
        children = restoreExpansion(section.children, collapsedSectionIds),
    )
}

/** Recursively flip isExpanded on the node whose project id is [projectId]. */
fun toggleSectionExpanded(sections: List<ProjectSection>, projectId: Long): List<ProjectSection> =
    sections.map { s ->
        if (s.project.id == projectId) {
            s.copy(isExpanded = !s.isExpanded)
        } else {
            s.copy(children = toggleSectionExpanded(s.children, projectId))
        }
    }

/** The section with [projectId], searched recursively, or null. */
fun findProjectSection(sections: List<ProjectSection>, projectId: Long): ProjectSection? {
    for (section in sections) {
        if (section.project.id == projectId) return section
        findProjectSection(section.children, projectId)?.let { return it }
    }
    return null
}

/**
 * Reorder within whichever section's task list holds both [fromId] and [toId]: returns the
 * rebuilt tree, or null when no section can absorb the move (task absent, cross-section, or a
 * dated row — all vetoed by [moveTaskInList]).
 */
fun moveTaskInSections(
    sections: List<ProjectSection>,
    fromId: Long,
    toId: Long,
): List<ProjectSection>? {
    var moved = false
    fun recurse(list: List<ProjectSection>): List<ProjectSection> = list.map { s ->
        if (moved) return@map s
        val reordered = moveTaskInList(s.tasks, fromId, toId)
        if (reordered != null) {
            moved = true
            s.copy(tasks = reordered)
        } else {
            s.copy(children = recurse(s.children))
        }
    }
    val result = recurse(sections)
    return if (moved) result else null
}

/** The section whose own task list contains [taskId], searched recursively, or null. */
fun findTaskGroup(sections: List<ProjectSection>, taskId: Long): ProjectSection? {
    for (s in sections) {
        if (s.tasks.any { it.id == taskId }) return s
        findTaskGroup(s.children, taskId)?.let { return it }
    }
    return null
}

/** True when any section anywhere in the tree has at least one task. */
fun hasAnyTask(sections: List<ProjectSection>): Boolean =
    sections.any { it.tasks.isNotEmpty() || hasAnyTask(it.children) }

/** Total tasks in [section] and all its descendant sections (for collapsed-section badges). */
fun totalTaskCount(section: ProjectSection): Int =
    section.tasks.size + section.children.sumOf { totalTaskCount(it) }
