package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Project

/**
 * Flatten projects into depth-first tree order: each parent immediately followed by its
 * children, paired with the nesting depth. Sibling order follows the input list order.
 * Orphans (parent not in the list) land at root level; the visited guard terminates on
 * pre-existing cyclic parent data (A->B->A).
 *
 * Callers are responsible for pre-filtering the input (e.g., archived projects); a project
 * whose parent was filtered out lands at root level via the orphan pass.
 */
fun buildProjectTree(projects: List<Project>): List<Pair<Project, Int>> {
    val childrenMap = projects.groupBy { it.parentProjectId }
    val result = mutableListOf<Pair<Project, Int>>()
    val visited = mutableSetOf<Long>()
    fun addChildren(parentId: Long, depth: Int) {
        childrenMap[parentId]?.forEach { project ->
            if (!visited.add(project.id)) return@forEach
            result.add(project to depth)
            addChildren(project.id, depth + 1)
        }
    }
    addChildren(0L, 0)
    // Add any orphans (parent not in list) at root level
    val addedIds = result.map { it.first.id }.toSet()
    projects.filter { it.id !in addedIds }.forEach { result.add(it to 0) }
    return result
}
