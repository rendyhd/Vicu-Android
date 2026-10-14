package com.rendyhd.vicu.ui.components.picker

import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.util.buildProjectTree

/** One row of the project picker. [parentTitle] is set only for a match in a search. */
internal data class PickerProjectRow(
    val project: Project,
    val depth: Int,
    val parentTitle: String? = null,
)

/**
 * The rows of the project picker. With no [query] they are the project tree (the Inbox first).
 * With one they are the projects whose title contains it, flat, each with the project it sits in,
 * so two projects of the same name can be told apart. Archived projects are never offered; the
 * non-archived children of an archived one surface at the root so they stay selectable.
 */
internal fun projectPickerRows(
    projects: List<Project>,
    inboxProjectId: Long,
    query: String,
): List<PickerProjectRow> {
    val visible = projects
        .filter { !it.isArchived }
        .sortedWith(compareBy({ it.id != inboxProjectId }, { it.position }, { it.title }))
    val tree = buildProjectTree(visible)
    val needle = query.trim()
    if (needle.isEmpty()) return tree.map { (project, depth) -> PickerProjectRow(project, depth) }

    val titleById = visible.associate { it.id to it.title }
    return tree
        .filter { (project, _) -> project.title.contains(needle, ignoreCase = true) }
        .map { (project, _) -> PickerProjectRow(project, depth = 0, parentTitle = titleById[project.parentProjectId]) }
}
