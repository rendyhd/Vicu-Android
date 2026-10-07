package com.rendyhd.vicu.data.local.dao

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
