package com.rendyhd.vicu.util.pro

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A device-only view of some categories and projects. */
data class ProFocus(val categories: List<String>, val projects: List<Long>)

/**
 * Focus (docs/cross-app-semantics-v1.md section 21.14), the same as the desktop's
 * `src/shared/pro/focus.ts`: a device-only view of some categories and projects. Never synced;
 * described so both apps filter the same way.
 */
object Focus {
    /** The stored focus, or null when there is none (or nothing in it). */
    fun readProFocus(value: JsonElement?): ProFocus? {
        val stored = value as? JsonObject ?: return null
        val categories = (stored["categories"] as? JsonArray)?.mapNotNull { it.asText()?.takeIf { id -> id.isNotEmpty() } }.orEmpty()
        val projects = (stored["projects"] as? JsonArray)?.mapNotNull { it.asWhole()?.takeIf { id -> id > 0 } }.orEmpty()
        return if (categories.isNotEmpty() || projects.isNotEmpty()) ProFocus(categories, projects) else null
    }

    /**
     * A project is in focus when it or an ancestor is a focused project, or its top-level ancestor's
     * category is a focused category. The Inbox is always in focus (capture never hides). No focus:
     * all.
     */
    fun inFocus(
        projectId: Long,
        focus: ProFocus?,
        projects: Map<Long, AreaProject>,
        categoryOfProject: (Long) -> String?,
        inboxProjectId: Long?,
    ): Boolean {
        if (focus == null) return true
        if (projectId == inboxProjectId) return true
        val seen = mutableSetOf<Long>()
        var current: Long? = projectId
        while (current != null && current > 0 && current !in seen) {
            if (current in focus.projects) return true
            seen.add(current)
            val parent = projects[current]?.parentProjectId
            current = if (parent != null && parent != 0L && parent in projects) parent else null
        }
        val category = categoryOfProject(ProSettings.topLevelProjectId(projectId, projects))
        return category != null && category in focus.categories
    }

    /**
     * The projects the sidebar shows in focus: those in focus and the ancestors of a focused project
     * (so the tree keeps its path).
     */
    fun projectsShownInFocus(
        focus: ProFocus?,
        projects: Map<Long, AreaProject>,
        categoryOfProject: (Long) -> String?,
        inboxProjectId: Long?,
    ): Set<Long> {
        val shown = mutableSetOf<Long>()
        for (id in projects.keys) {
            if (!inFocus(id, focus, projects, categoryOfProject, inboxProjectId)) continue
            shown.add(id)
            var parent = projects[id]?.parentProjectId
            val seen = mutableSetOf(id)
            while (parent != null && parent != 0L && parent in projects && parent !in seen) {
                shown.add(parent)
                seen.add(parent)
                parent = projects[parent]?.parentProjectId
            }
        }
        return shown
    }
}
