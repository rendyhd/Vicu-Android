package com.rendyhd.vicu.ui.components.picker

import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.ui.navigation.buildProjectTree
import com.rendyhd.vicu.ui.navigation.visibleProjectRows
import com.rendyhd.vicu.util.ProjectCategory
import com.rendyhd.vicu.util.ProjectCategoryState

/**
 * One row of the project picker. [parentTitle] is set only for a match in a search. [categoryHeader]
 * is the category whose header goes above this row: the first row of each category's group.
 */
internal data class PickerProjectRow(
    val project: Project,
    val depth: Int,
    val parentTitle: String? = null,
    val categoryHeader: ProjectCategory? = null,
)

/**
 * The rows of the project picker. With no [query] they are the project tree (the Inbox first).
 * With one they are the projects whose title contains it, flat, each with the project it sits in,
 * so two projects of the same name can be told apart. Archived projects are never offered; the
 * non-archived children of an archived one surface at the root, after the other top-level projects,
 * so they stay selectable.
 *
 * With [categories] the tree is grouped as the drawer groups it (section 13): the Inbox first, the
 * projects without a category, then each category's projects under its header. Empty categories
 * have nothing to pick and no header.
 */
internal fun projectPickerRows(
    projects: List<Project>,
    inboxProjectId: Long,
    query: String,
    categories: ProjectCategoryState = ProjectCategoryState.EMPTY,
): List<PickerProjectRow> {
    val visible = projects.filter { !it.isArchived }
    val order = compareBy<Project>({ it.id != inboxProjectId }, { it.parentProjectId != 0L }, { it.position }, { it.title })
    val tree = visibleProjectRows(buildProjectTree(visible, order), collapsed = emptySet()).map { it.project to it.depth }
    val needle = query.trim()
    if (needle.isEmpty() && categories.categories.isEmpty()) return tree.map { (project, depth) -> PickerProjectRow(project, depth) }
    if (needle.isEmpty()) {
        // Each top-level project with the rows below it, kept together while the groups are made.
        val blocks = ArrayList<MutableList<Pair<Project, Int>>>()
        for (entry in tree) {
            if (entry.second == 0 || blocks.isEmpty()) blocks += mutableListOf(entry) else blocks.last() += entry
        }
        val inbox = blocks.filter { it.first().first.id == inboxProjectId }
        val groups = categories.group(blocks - inbox.toSet()) { it.first().first.id }
        return buildList {
            inbox.flatten().forEach { (project, depth) -> add(PickerProjectRow(project, depth)) }
            for (group in groups) {
                group.items.flatten().forEachIndexed { index, (project, depth) ->
                    add(PickerProjectRow(project, depth, categoryHeader = group.category.takeIf { index == 0 }))
                }
            }
        }
    }

    val titleById = visible.associate { it.id to it.title }
    return tree
        .filter { (project, _) -> project.title.contains(needle, ignoreCase = true) }
        .map { (project, _) -> PickerProjectRow(project, depth = 0, parentTitle = titleById[project.parentProjectId]) }
}
