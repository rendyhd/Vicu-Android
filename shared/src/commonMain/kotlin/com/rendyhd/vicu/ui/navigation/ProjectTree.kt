package com.rendyhd.vicu.ui.navigation

import com.rendyhd.vicu.domain.model.Project

/** A project and the projects below it, at any depth. */
data class ProjectNode(
    val project: Project,
    val children: List<ProjectNode>,
)

/**
 * One row of the drawer's project list: a project of the tree that is on show.
 *
 * @param depth how many projects are above it (0 for a root)
 * @param parentId the project above it in the tree, 0 for a root. That is not always the project's
 *   own parent: one whose parent is archived, or is the Inbox, is listed as a root.
 * @param hasChildren whether projects are nested below it
 * @param expanded whether those projects are on show (false when it has none)
 */
data class ProjectRow(
    val project: Project,
    val depth: Int,
    val parentId: Long,
    val hasChildren: Boolean,
    val expanded: Boolean,
)

private val SIBLING_ORDER = compareBy<Project>({ it.position }, { it.id })

/**
 * The tree of [projects] (the ones to list: no archived project, no Inbox). Siblings come in
 * position order, ties by id so the order is stable. A project whose parent is not in [projects]
 * is a root. Projects caught in a loop of parents (A below B below A) are listed too, at the top,
 * rather than vanishing.
 */
fun buildProjectTree(projects: List<Project>): List<ProjectNode> {
    val ids = projects.mapTo(HashSet()) { it.id }
    fun hasListedParent(p: Project) = p.parentProjectId != 0L && p.parentProjectId != p.id && p.parentProjectId in ids

    val childrenOf = projects.filter(::hasListedParent).groupBy { it.parentProjectId }
        .mapValues { (_, children) -> children.sortedWith(SIBLING_ORDER) }

    val placed = HashSet<Long>()
    fun nodeOf(project: Project): ProjectNode {
        placed += project.id
        // A parent loop leads back to a project that is already placed; it is not placed twice.
        val children = childrenOf[project.id].orEmpty().filter { it.id !in placed }.map { nodeOf(it) }
        return ProjectNode(project, children)
    }

    val tree = ArrayList<ProjectNode>()
    for (root in projects.filterNot(::hasListedParent).sortedWith(SIBLING_ORDER)) {
        if (root.id !in placed) tree += nodeOf(root)
    }
    // Whatever the roots did not reach hangs in a loop of parents: start at the first of each.
    for (stray in projects.sortedWith(SIBLING_ORDER)) {
        if (stray.id !in placed) tree += nodeOf(stray)
    }
    return tree
}

/**
 * The rows on show: the tree in display order, leaving out what is below a project in [collapsed].
 * A collapsed project that has no children is shown as it is.
 */
fun visibleProjectRows(tree: List<ProjectNode>, collapsed: Set<Long>): List<ProjectRow> {
    val rows = ArrayList<ProjectRow>()
    fun add(node: ProjectNode, depth: Int, parentId: Long) {
        val hasChildren = node.children.isNotEmpty()
        val expanded = hasChildren && node.project.id !in collapsed
        rows += ProjectRow(node.project, depth, parentId, hasChildren, expanded)
        if (expanded) node.children.forEach { add(it, depth + 1, node.project.id) }
    }
    tree.forEach { add(it, 0, 0L) }
    return rows
}

/**
 * The order of [rows] with [movedId] (and the rows on show below it) dragged over [targetId], or
 * null when that is no move: either is not listed, they are the same row, or they are not on the
 * same level. A project is only ever put among its own siblings.
 *
 * A [targetId] that is a row below a sibling (a child of the project above, say) stands for that
 * sibling, so a drag over an open project goes past all of its children, not into the middle of
 * them. The row lands before the sibling when it came from below and after it when it came from above.
 */
fun moveProjectRow(rows: List<ProjectRow>, movedId: Long, targetId: Long): List<ProjectRow>? {
    val movedIndex = rows.indexOfFirst { it.project.id == movedId }
    val targetIndex = rows.indexOfFirst { it.project.id == targetId }
    if (movedIndex < 0 || targetIndex < 0 || movedIndex == targetIndex) return null
    val moved = rows[movedIndex]

    var sibling = rows[targetIndex]
    while (sibling.parentId != moved.parentId) {
        if (sibling.parentId == 0L) return null
        sibling = rows.firstOrNull { it.project.id == sibling.parentId } ?: return null
    }
    if (sibling.project.id == movedId) return null

    val siblingIndex = rows.indexOfFirst { it.project.id == sibling.project.id }
    val movedEnd = blockEnd(rows, movedIndex)
    val block = rows.subList(movedIndex, movedEnd).toList()
    val rest = rows.subList(0, movedIndex) + rows.subList(movedEnd, rows.size)
    val insertAt = if (siblingIndex < movedIndex) siblingIndex else blockEnd(rows, siblingIndex) - block.size
    return rest.toMutableList().apply { addAll(insertAt, block) }
}

/** The index after the last row of the block that starts at [start]: the row and the deeper rows after it. */
private fun blockEnd(rows: List<ProjectRow>, start: Int): Int {
    var end = start + 1
    while (end < rows.size && rows[end].depth > rows[start].depth) end++
    return end
}

/** The ids of the rows on the same level as [projectId], in display order. Empty when it is not listed. */
fun siblingIds(rows: List<ProjectRow>, projectId: Long): List<Long> {
    val parentId = rows.firstOrNull { it.project.id == projectId }?.parentId ?: return emptyList()
    return rows.filter { it.parentId == parentId }.map { it.project.id }
}

/**
 * The tree with the children of [parentId] (the roots for 0) in the order of [ids]. Projects the
 * list does not name follow, in the order they had; ids that are not there are ignored.
 */
fun reorderSiblings(tree: List<ProjectNode>, parentId: Long, ids: List<Long>): List<ProjectNode> =
    if (parentId == 0L) {
        orderedBy(tree, ids) { it.project.id }
    } else {
        tree.map { node ->
            node.copy(
                children = if (node.project.id == parentId) {
                    orderedBy(node.children, ids) { it.project.id }
                } else {
                    reorderSiblings(node.children, parentId, ids)
                },
            )
        }
    }
