package com.rendyhd.vicu.domain.model

/**
 * How the tasks of a project are ordered: in the project screen, the Inbox, and the widgets that
 * show them. Chosen for every project in Settings and overridden per project from its screen; a
 * project without its own choice follows its nearest parent that has one, then the global choice.
 * Stored on the device only ([key] is the stored form, never rename one).
 */
enum class TaskOrder(val key: String, val label: String) {
    /** Dated tasks first by due date, then the rest in their manual order: the desktop's order. */
    DUE_DATE_FIRST("due_date_first", "Due date, then manual"),

    /** Dated tasks first by due date, then the rest by priority (highest first), then manual. */
    DUE_DATE_THEN_PRIORITY("due_date_priority", "Due date, then priority"),

    /** Every task in its manual order, dated or not; every row can be dragged. */
    MANUAL("manual", "Manual"),

    /** Highest priority first, then by due date, then in manual order. */
    PRIORITY("priority", "Priority"),

    /** By title, A to Z. */
    TITLE("title", "Title"),

    /** The most recently created first. */
    NEWEST("newest", "Newest first"),

    /** The longest-standing first. */
    OLDEST("oldest", "Oldest first"),
    ;

    companion object {
        val DEFAULT = DUE_DATE_FIRST

        fun fromKey(key: String?): TaskOrder? = entries.firstOrNull { it.key == key }
    }
}

/**
 * The global order and the projects that have their own. [orderFor] resolves the order a project
 * actually uses.
 */
data class TaskOrderSettings(
    val global: TaskOrder = TaskOrder.DEFAULT,
    val projectOverrides: Map<Long, TaskOrder> = emptyMap(),
) {
    /**
     * The order [projectId] uses: its own, else that of its nearest parent with one, else the
     * global one. [projects] gives the parents; a cycle in old parent data ends the walk.
     */
    fun orderFor(projectId: Long, projects: List<Project>): TaskOrder =
        orderFor(projectId, projects.associate { it.id to it.parentProjectId })

    /** [orderFor] with the parents given as a map from project id to parent id (0 for none). */
    fun orderFor(projectId: Long, parentOf: Map<Long, Long>): TaskOrder =
        projectOverrides[projectId] ?: inheritedOrderFor(projectId, parentOf)

    /** The order [projectId] would use without its own choice: what "Default" means for it. */
    fun inheritedOrderFor(projectId: Long, projects: List<Project>): TaskOrder =
        inheritedOrderFor(projectId, projects.associate { it.id to it.parentProjectId })

    /** [inheritedOrderFor] with the parents given as a map from project id to parent id (0 for none). */
    fun inheritedOrderFor(projectId: Long, parentOf: Map<Long, Long>): TaskOrder {
        if (projectOverrides.isEmpty()) return global
        val visited = mutableSetOf(projectId)
        var current = parentOf[projectId] ?: 0L
        while (current != 0L && visited.add(current)) {
            projectOverrides[current]?.let { return it }
            current = parentOf[current] ?: 0L
        }
        return global
    }
}
