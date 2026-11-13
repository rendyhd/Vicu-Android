package com.rendyhd.vicu.util

/**
 * Which note link of a task may be opened (docs/description-format-v1.md, "Task links"; vectors
 * `noteLinks` of test-fixtures/description-format-v1.json; desktop: `src/shared/note-links.ts`).
 *
 * A task's `notelink` marker can be written by anyone who may edit the task, and an `obsidian:`
 * address can do more than open a note: with the Advanced URI plugin it runs commands and writes
 * files. So only the two shapes Vicu writes itself are offered: `obsidian://open` with `vault` and
 * `file`, and `obsidian://advanced-uri` with `vault` and `uid`. The test is on the text, not on a
 * parsed URL, so every platform decides the same way.
 */
object NoteLinks {

    /** The longest note link that is offered. */
    const val MAX_CHARS = 2048

    private const val PREFIX = "obsidian://"

    /** The parameter each allowed action takes next to `vault`. */
    private val actions = mapOf("open" to "file", "advanced-uri" to "uid")

    /** True when [url] is a note link in one of the two shapes Vicu writes. */
    fun isAllowed(url: String?): Boolean {
        if (url == null || url.length > MAX_CHARS || !url.startsWith(PREFIX)) return false
        // A fragment, a quote, an angle bracket, a space or a control character has no place in the address.
        if (url.any { it.code <= 0x20 || it.code == 0x7f || it == '"' || it == '<' || it == '>' || it == '#' }) return false

        val rest = url.substring(PREFIX.length)
        val queryStart = rest.indexOf('?')
        if (queryStart < 0) return false
        val second = actions[rest.substring(0, queryStart)] ?: return false

        val pairs = rest.substring(queryStart + 1).split('&')
        if (pairs.size != 2) return false
        val values = mutableMapOf<String, String>()
        for (pair in pairs) {
            val equals = pair.indexOf('=')
            if (equals <= 0) return false
            val name = pair.substring(0, equals)
            if (name in values) return false
            values[name] = pair.substring(equals + 1)
        }
        return "vault" in values && !values[second].isNullOrEmpty()
    }
}
