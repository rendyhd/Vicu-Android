package com.rendyhd.vicu.util

/**
 * Builds `LIKE` patterns from text a user typed. `%`, `_` and the escape character itself are
 * wildcards or escapes in a `LIKE` pattern; searching for "100%" or "a_b" must match those
 * characters, not anything. Queries that use these patterns say `ESCAPE '\'`.
 */
object SqlLike {
    const val ESCAPE_CHAR = '\\'

    /** [text] with every `%`, `_` and escape character escaped, to match literally. */
    fun escape(text: String): String = buildString(text.length + 4) {
        for (c in text) {
            if (c == '%' || c == '_' || c == ESCAPE_CHAR) append(ESCAPE_CHAR)
            append(c)
        }
    }

    /** A pattern that matches anything containing [text]. */
    fun contains(text: String): String = "%${escape(text)}%"
}
