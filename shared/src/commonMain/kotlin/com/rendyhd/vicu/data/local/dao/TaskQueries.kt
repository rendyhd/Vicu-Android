package com.rendyhd.vicu.data.local.dao

import com.rendyhd.vicu.data.local.entity.TaskEntity

/**
 * The search query, kept as a constant so a unit test can run the very text Room compiles against
 * a real SQLite (it escapes nothing itself: the caller binds a pattern from
 * [com.rendyhd.vicu.util.SqlLike.contains]).
 *
 * Title or description contains the text, metadata tasks never. Open tasks come first, newest
 * change first within each group; the cap keeps a one-letter query from mapping the whole table.
 */
internal const val TASK_SEARCH_SQL = """
    SELECT * FROM tasks
    WHERE isMetadata = 0
      AND (title LIKE :pattern ESCAPE '\' OR description LIKE :pattern ESCAPE '\')
    ORDER BY done ASC, updated DESC, id DESC
    LIMIT 300
"""

/**
 * The Inbox: open tasks of the Inbox project, in the order of the project's list view. The
 * position is what the user dragged (or where a new task was put: the end); tasks with equal
 * positions, which includes every one whose position is not known, are oldest first.
 */
internal const val INBOX_TASKS_SQL = """
    SELECT * FROM tasks
    WHERE done = 0 AND isMetadata = 0 AND projectId = :inboxProjectId
    AND (:includeDated = 1 OR dueDate = '' OR dueDate = '0001-01-01T00:00:00Z')
    ORDER BY position ASC, created ASC, id ASC
"""

/**
 * Per project: the open tasks and the done tasks the phone holds, hidden carrier and archive
 * tasks left out (the drawer's progress rings). A project with no task here has no row.
 */
internal const val PROJECT_TALLIES_SQL = """
    SELECT projectId,
           SUM(CASE WHEN done = 0 THEN 1 ELSE 0 END) AS open,
           SUM(CASE WHEN done = 1 THEN 1 ELSE 0 END) AS doneOnPhone
    FROM tasks
    WHERE isMetadata = 0
    GROUP BY projectId
"""

/** One row of [PROJECT_TALLIES_SQL]. */
data class ProjectTallyRow(val projectId: Long, val open: Int, val doneOnPhone: Int)

/** A hidden carrier or archive task the phone holds, and the project it lives in. */
data class MetadataTaskRef(val id: Long, val projectId: Long)

/** A task's stored list-view position, as [TaskDao.getStoredPositionsChunk] returns it. */
data class StoredPosition(val id: Long, val position: Double)

/**
 * [tasks] with the stored position put back on every task that arrived without one (position 0).
 * A task that arrived with a position keeps it: that is the server speaking about a list view.
 */
fun keepStoredPositions(tasks: List<TaskEntity>, stored: Map<Long, Double>): List<TaskEntity> =
    if (stored.isEmpty()) {
        tasks
    } else {
        tasks.map { task ->
            val kept = if (task.position == 0.0) stored[task.id] else null
            if (kept != null) task.copy(position = kept) else task
        }
    }
