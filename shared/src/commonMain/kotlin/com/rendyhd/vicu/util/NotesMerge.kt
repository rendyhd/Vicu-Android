package com.rendyhd.vicu.util

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Notes changed in two places (docs/description-format-v1.md, "Synchronization"; vectors
 * `notesConflict` of test-fixtures/description-format-v1.json; desktop: `src/shared/notes-merge.ts`,
 * which this ports line for line).
 *
 * A notes edit remembers the description it started from (the base). Before it is sent, the task is
 * read: when the server still holds the base, the edit is sent; when the server holds something
 * else, both versions are kept, the server's first, then a divider, then this device's. Nothing is
 * ever dropped, and no question is asked, because a queued edit is sent when nobody is looking.
 *
 * Works on the stored text only (no HTML parser), so every platform gives the same result.
 */
object NotesMerge {

    sealed interface Decision {
        /** Nothing was changed, or the server's notes already hold all of this device's: no description is sent. */
        data object NoChange : Decision

        /** The server still holds the base: the edit is sent as it is. */
        data class Send(val description: String) : Decision

        /** Both sides changed: this merged description is sent instead. */
        data class Merge(val description: String) : Decision
    }

    /** The divider between the two versions of merged notes. */
    const val DIVIDER = "<hr>"

    /** What the app says once when it sent merged notes. */
    const val MERGED_MESSAGE = "Notes were also changed on another device. Both versions are kept."

    /**
     * Where a queued notes edit keeps its base in the payload JSON. Never sent: the replay takes it
     * out of the patch first ([fromQueued]).
     */
    const val BASE_KEY = "vicu_base_description"

    private val LINK_PAIR =
        Regex("""<!-- (?:notelink:obsidian|pagelink:https?)://[^>]+ -->(?:<p><a href="[^"]*">[^<]*</a></p>)?""")
    private val TOKEN_LINE = Regex("""\[\[image(?:-pending)?:[^\]\n]*\]\]""")

    private class NotesParts(
        val body: String,
        /** Image tokens that stand on a line of their own, in order. */
        val tokens: List<String>,
        /** The notelink and pagelink pairs, as stored. */
        val links: List<String>,
    )

    /** Space, tab and line ends off both ends (named, so no platform's own trim can differ). */
    internal fun trimmed(value: String): String {
        var start = 0
        var end = value.length
        while (start < end && isEdge(value[start])) start++
        while (end > start && isEdge(value[end - 1])) end--
        return value.substring(start, end)
    }

    private fun isEdge(c: Char): Boolean = c == ' ' || c == '\t' || c == '\r' || c == '\n'

    /** A description taken apart: the pairs and the token lines leave, the rest is the body. */
    private fun splitNotes(description: String): NotesParts {
        val links = mutableListOf<String>()
        val withoutLinks = LINK_PAIR.replace(description) { pair ->
            links.add(pair.value)
            ""
        }
        val tokens = mutableListOf<String>()
        val bodyLines = mutableListOf<String>()
        for (line in withoutLinks.split("\n")) {
            val token = trimmed(line)
            if (TOKEN_LINE.matches(token)) tokens.add(token) else bodyLines.add(line)
        }
        return NotesParts(body = trimmed(bodyLines.joinToString("\n")), tokens = tokens, links = links)
    }

    /** The parts put together again: body, one token a line, then the pairs. */
    private fun joinNotes(parts: NotesParts): String =
        (listOf(parts.body) + parts.tokens + listOf(parts.links.joinToString("")))
            .filter { it != "" }
            .joinToString("\n")

    /**
     * Both versions in one description: the server's body, the divider, this device's body (one body
     * when they are the same, one is empty, or the server's already ends with the divider and this
     * device's); the server's token lines, then this device's that the server lacks; the link pairs
     * the same way.
     */
    fun mergeNotes(server: String?, local: String?): String {
        val theirs = splitNotes(server.orEmpty())
        val mine = splitNotes(local.orEmpty())
        val body =
            // The last case: an earlier send of these merged notes arrived, and only its answer was lost.
            if (theirs.body == mine.body || mine.body == "" || theirs.body.endsWith(DIVIDER + mine.body)) {
                theirs.body
            } else if (theirs.body == "") {
                mine.body
            } else {
                "${theirs.body}$DIVIDER${mine.body}"
            }
        val tokens = theirs.tokens + mine.tokens.filter { it !in theirs.tokens }
        val links = theirs.links + mine.links.filter { it !in theirs.links }
        return joinNotes(NotesParts(body, tokens, links))
    }

    /**
     * What a notes edit sends. [base] is the description the edit started from, [local] what the user
     * wants saved, [server] the description just read. Compared as stored text with the space, tabs
     * and line ends at both ends left out; nothing else is normalised.
     */
    fun decideNotesSave(base: String?, local: String?, server: String?): Decision {
        val from = trimmed(base.orEmpty())
        val mine = trimmed(local.orEmpty())
        val theirs = trimmed(server.orEmpty())
        if (mine == theirs || mine == from) return Decision.NoChange
        if (theirs == from) return Decision.Send(local.orEmpty())
        val merged = mergeNotes(theirs, mine)
        // Nothing of this device's is missing from the server's notes: it only took text away.
        if (merged == theirs) return Decision.NoChange
        // Nothing of the server's is missing from this device's notes: the server only took text away.
        if (merged == mine) return Decision.Send(local.orEmpty())
        return Decision.Merge(merged)
    }

    // --- The queued payload --------------------------------------------------------------------

    /** [patch] with the base kept for the replay; unchanged when [base] is null or the patch has no description. */
    fun withBase(patch: JsonObject, base: String?): JsonObject =
        if (base == null || "description" !in patch) patch else JsonObject(patch + (BASE_KEY to JsonPrimitive(base)))

    /**
     * A queued task change as stored: [patch] is what goes to the server (never the base key),
     * [base] is non-null when the change sets the notes and has to be checked first.
     */
    data class Queued(val patch: JsonObject, val base: String?)

    /** Splits a stored task patch. A payload without the key (an older version, a writer without a base) is sent as before. */
    fun fromQueued(stored: JsonObject): Queued {
        val raw = stored[BASE_KEY] ?: return Queued(stored, null)
        val patch = JsonObject(stored - BASE_KEY)
        if ("description" !in patch) return Queued(patch, null)
        val base = when (raw) {
            is JsonNull -> null
            is JsonPrimitive -> raw.content
            else -> null
        }
        return Queued(patch, base)
    }

    /**
     * Two queued patches of one task were folded into [merged], where the later values win; the
     * base, though, stays the one of [first], the earlier notes edit ("Edits of one task that fold into one queued change
     * keep the base of the first"). A first edit of the notes without a base leaves the fold without
     * one, so it is sent as it is.
     */
    fun foldBase(first: JsonObject, merged: JsonObject): JsonObject = when {
        "description" in first -> {
            val keep = first[BASE_KEY]
            if (keep == null) JsonObject(merged - BASE_KEY) else JsonObject(merged + (BASE_KEY to keep))
        }
        else -> merged
    }

    /**
     * What a patch sends once the decision is known: [Decision.NoChange] takes `description` out,
     * [Decision.Merge] puts the merged notes in, [Decision.Send] leaves it as it is.
     */
    fun applyTo(patch: JsonObject, decision: Decision): JsonObject = when (decision) {
        Decision.NoChange -> JsonObject(patch - "description")
        is Decision.Send -> patch
        is Decision.Merge -> JsonObject(patch + ("description" to JsonPrimitive(decision.description)))
    }
}
