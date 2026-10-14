package com.rendyhd.vicu.util

import kotlinx.datetime.Instant

/**
 * Collaboration rules shared with the desktop app (docs/cross-app-semantics-v1.md section 9, pinned
 * by the `collaboration` vectors of test-fixtures/cross-app-semantics-v1.json). The desktop has the
 * same rules in `src/shared/collaboration.ts`.
 *
 * Pure: comment HTML is read with a small tokenizer of its own, so both apps read it the same way
 * whatever the platform's HTML parser would do.
 */

/** A person as the API returns one: `User` without the fields Vicu does not use. */
data class CollabUser(val id: Long, val username: String, val name: String? = null)

/** Read only, read and write, admin (`max_permission`, a share's `permission`). */
object Permission {
    const val READ = 0
    const val WRITE = 1
    const val ADMIN = 2
}

data class ProjectNode(val id: Long, val parent: Long, val owner: Long)
data class UserShareEntry(val user: Long, val permission: Int)
data class TeamShareEntry(val permission: Int, val members: List<Long>)

data class ShareGraph(
    val projects: List<ProjectNode>,
    /** Direct user shares per project id. */
    val shares: Map<Long, List<UserShareEntry>>,
    /** Team shares per project id. */
    val teams: Map<Long, List<TeamShareEntry>>,
)

sealed interface CommentSegment {
    data class Text(val text: String) : CommentSegment
    data class Mention(val mention: String, val label: String) : CommentSegment
}

data class DecodedComment(
    val segments: List<CommentSegment>,
    /** Only `p`, `br` and `mention-user` elements: Vicu can edit it without losing anything. */
    val plain: Boolean,
    /** The text the editor starts from (mentions as `@username`); null when the comment is not plain. */
    val editText: String?,
)

data class MentionQuery(
    /** What follows the `@`, as typed. */
    val query: String,
    /** The index of the `@`. */
    val start: Int,
)

data class MentionEdit(val text: String, val caret: Int)

object Collaboration {

    // --- People and names --------------------------------------------------------------------

    /** `name`, trimmed, when it is not empty; otherwise the username. */
    fun displayName(user: CollabUser): String = user.name?.trim().orEmpty().ifEmpty { user.username }

    /** You first, then everyone else in the order the server returned them. */
    fun <T> orderPeople(people: List<T>, me: Long?, idOf: (T) -> Long): List<T> {
        if (me == null) return people.toList()
        return people.filter { idOf(it) == me } + people.filter { idOf(it) != me }
    }

    fun orderPeople(people: List<CollabUser>, me: Long?): List<CollabUser> = orderPeople(people, me) { it.id }

    private fun namesOf(people: List<CollabUser>, me: Long?, you: String): List<String> =
        orderPeople(people, me).map { if (it.id == me) you else displayName(it) }

    /** "You, Maya": the names joined with ", ", you first as "You". */
    fun nameList(people: List<CollabUser>, me: Long?): String = namesOf(people, me, "You").joinToString(", ")

    /** "Visible to you, Maya and Sam"; empty when there is nobody. */
    fun visibleToText(people: List<CollabUser>, me: Long?): String {
        val names = namesOf(people, me, "you")
        return when (names.size) {
            0 -> ""
            1 -> "Visible to ${names[0]}"
            else -> "Visible to ${names.dropLast(1).joinToString(", ")} and ${names.last()}"
        }
    }

    /** The accessible name of a row's avatars: "Assigned to you, Maya"; empty when nobody is assigned. */
    fun assignedLabel(people: List<CollabUser>, me: Long?): String {
        val names = namesOf(people, me, "you")
        return if (names.isEmpty()) "" else "Assigned to ${names.joinToString(", ")}"
    }

    /** The accessible name of the Assign chip: "Assigned: You, Maya", or "Assign" when nobody is assigned. */
    fun assignChipLabel(people: List<CollabUser>, me: Long?): String =
        if (people.isEmpty()) "Assign" else "Assigned: ${nameList(people, me)}"

    /** "1 comment", "4 comments", "2 comments, new". */
    fun commentCountLabel(count: Int, unread: Boolean): String =
        "$count ${if (count == 1) "comment" else "comments"}${if (unread) ", new" else ""}"

    /** The letter of the avatar fallback: the first character of the display name, in upper case. */
    fun avatarInitial(user: CollabUser): String {
        val name = displayName(user)
        if (name.isEmpty()) return "?"
        val end = if (name[0].isHighSurrogate() && name.length > 1 && name[1].isLowSurrogate()) 2 else 1
        return name.substring(0, end).uppercase()
    }

    // --- Sharing and permissions -------------------------------------------------------------

    /** A project is shared when its members include someone who is not you (section 9.1). */
    fun isSharedProject(memberIds: List<Long>, me: Long?): Boolean = me != null && memberIds.any { it != me }

    /** Your permission on a project: archived counts as read only, unknown as read and write. */
    fun effectivePermission(maxPermission: Int?, archived: Boolean = false): Int = when {
        archived -> Permission.READ
        maxPermission == 0 || maxPermission == 1 || maxPermission == 2 -> maxPermission
        else -> Permission.WRITE
    }

    /** The project and its parents, nearest first. Stops at a missing parent or a cycle. */
    fun projectChain(projectId: Long, projects: List<ProjectNode>): List<ProjectNode> {
        val byId = projects.associateBy { it.id }
        val chain = mutableListOf<ProjectNode>()
        val seen = mutableSetOf<Long>()
        var current = byId[projectId]
        while (current != null && current.id !in seen) {
            chain += current
            seen += current.id
            current = if (current.parent > 0) byId[current.parent] else null
        }
        return chain
    }

    /**
     * A member's permission for the "Can view only" note (section 9.1): 2 for the owner of the
     * project or a parent; otherwise the highest direct or team share on the project and its
     * parents; null when nothing is known.
     */
    fun memberPermission(projectId: Long, userId: Long, graph: ShareGraph): Int? {
        val chain = projectChain(projectId, graph.projects)
        if (chain.any { it.owner == userId }) return Permission.ADMIN
        var best: Int? = null
        for (node in chain) {
            for (share in graph.shares[node.id].orEmpty()) {
                if (share.user == userId && (best == null || share.permission > best)) best = share.permission
            }
            for (team in graph.teams[node.id].orEmpty()) {
                if (userId in team.members && (best == null || team.permission > best)) best = team.permission
            }
        }
        return best
    }

    /** Whether a member can be picked in Assign: unknown counts as read and write. */
    fun canMemberWrite(permission: Int?): Boolean = permission == null || permission >= Permission.WRITE

    /**
     * Whether Notify me is offered for a sign-in [authMethod] (section 9.7): Vikunja does not let
     * API tokens follow tasks, so only password and SSO sign-ins can.
     */
    fun canFollowTasks(authMethod: String?): Boolean = authMethod == "password" || authMethod == "oidc"

    // --- Comment HTML ------------------------------------------------------------------------

    /** A letter, a decimal digit or `_`, looked at one UTF-16 unit at a time. */
    private fun isWordChar(ch: Char?): Boolean =
        ch != null && !ch.isSurrogate() && (ch.isLetter() || ch.category == CharCategory.DECIMAL_DIGIT_NUMBER || ch == '_')

    private val TRIM_CHARS = setOf(' ', '\t', '\n', '\r', ' ')

    private fun trimStart(text: String): String = text.dropWhile { it in TRIM_CHARS }
    private fun trimEnd(text: String): String = text.dropLastWhile { it in TRIM_CHARS }
    private fun trimBoth(text: String): String = trimEnd(trimStart(text))

    private fun escapeHtml(text: String): String {
        val out = StringBuilder()
        for (ch in text) {
            when (ch) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    /**
     * The HTML a comment is sent as (section 9.5): one paragraph, `<br>` for line breaks, escaped
     * text, and each `@username` of a member as a `mention-user` element so the server notifies
     * them. Empty when there is nothing to send.
     */
    fun encodeCommentHtml(text: String, members: List<CollabUser>): String {
        val normalized = trimBoth(text.replace("\r\n", "\n").replace('\r', '\n'))
        if (normalized.isEmpty()) return ""
        // Longest username first; equal lengths keep the members' order (sortedByDescending is stable).
        val candidates = members.filter { it.username.isNotEmpty() }.sortedByDescending { it.username.length }
        val out = StringBuilder()
        var i = 0
        while (i < normalized.length) {
            val ch = normalized[i]
            if (ch == '@' && (i == 0 || !isWordChar(normalized[i - 1]))) {
                val member = candidates.firstOrNull { m ->
                    val end = i + 1 + m.username.length
                    end <= normalized.length &&
                        normalized.substring(i + 1, end).lowercase() == m.username.lowercase() &&
                        (end >= normalized.length || !isWordChar(normalized[end]))
                }
                if (member != null) {
                    val username = escapeHtml(member.username)
                    out.append("<mention-user data-id=\"").append(username).append("\" data-label=\"")
                        .append(escapeHtml(displayName(member))).append("\">@").append(username).append("</mention-user>")
                    i += 1 + member.username.length
                    continue
                }
            }
            if (ch == '\n') out.append("<br>") else out.append(escapeHtml(ch.toString()))
            i++
        }
        return "<p>$out</p>"
    }

    private val NAMED_REFERENCES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")
    private val REFERENCE = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[A-Za-z]+);")

    private fun fromCodePoint(code: Int): String {
        if (code < 0x10000) return code.toChar().toString()
        val v = code - 0x10000
        return charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
    }

    /** Decode the character references section 9.5 names; any other stays as written. */
    fun decodeReferences(text: String): String = REFERENCE.replace(text) { match ->
        val whole = match.value
        val body = match.groupValues[1]
        if (body[0] == '#') {
            val hex = body.length > 1 && (body[1] == 'x' || body[1] == 'X')
            val digits = if (hex) body.substring(2) else body.substring(1)
            val code = digits.trimStart('0').let { trimmed ->
                when {
                    trimmed.isEmpty() -> 0L
                    trimmed.length > 8 -> Long.MAX_VALUE
                    else -> trimmed.toLong(if (hex) 16 else 10)
                }
            }
            if (code <= 0 || code > 0x10FFFF || code in 0xD800..0xDFFF) whole else fromCodePoint(code.toInt())
        } else {
            NAMED_REFERENCES[body] ?: whole
        }
    }

    private val BLOCK_TAGS = setOf("p", "div", "li", "blockquote", "pre", "h1", "h2", "h3", "h4", "h5", "h6")
    private val PLAIN_TAGS = setOf("p", "br", "mention-user")
    private val SKIPPED_CONTENT = setOf("script", "style")

    private class ParsedTag(val closing: Boolean, val selfClosing: Boolean, val name: String, val attrs: Map<String, String>)

    private fun isAsciiLetter(ch: Char?): Boolean = ch != null && (ch in 'A'..'Z' || ch in 'a'..'z')
    private fun isSpace(ch: Char?): Boolean = ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' || ch == '\u000C'
    private fun isNameChar(ch: Char): Boolean = ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-'

    /** The index of the `>` that ends the tag starting at [start], outside quoted values; -1 when none. */
    private fun tagEnd(html: String, start: Int): Int {
        var quote: Char? = null
        for (i in start + 1 until html.length) {
            val ch = html[i]
            if (quote != null) {
                if (ch == quote) quote = null
            } else if (ch == '"' || ch == '\'') {
                quote = ch
            } else if (ch == '>') {
                return i
            }
        }
        return -1
    }

    private fun parseTag(inner: String): ParsedTag {
        var i = 0
        var closing = false
        if (inner.startsWith('/')) {
            closing = true
            i = 1
        }
        val name = StringBuilder()
        while (i < inner.length && isNameChar(inner[i])) name.append(inner[i++])
        val attrs = mutableMapOf<String, String>()
        var selfClosing = false
        while (i < inner.length) {
            val ch = inner[i]
            if (isSpace(ch)) {
                i++
                continue
            }
            if (ch == '/') {
                selfClosing = true
                i++
                continue
            }
            val attrName = StringBuilder()
            while (i < inner.length && !isSpace(inner[i]) && inner[i] != '=' && inner[i] != '/') attrName.append(inner[i++])
            while (isSpace(inner.getOrNull(i))) i++
            var value = ""
            if (inner.getOrNull(i) == '=') {
                i++
                while (isSpace(inner.getOrNull(i))) i++
                val quote = inner.getOrNull(i)
                if (quote == '"' || quote == '\'') {
                    val close = inner.indexOf(quote, i + 1)
                    val end = if (close == -1) inner.length else close
                    value = inner.substring(i + 1, end)
                    i = end + 1
                } else {
                    val v = StringBuilder()
                    while (i < inner.length && !isSpace(inner[i])) v.append(inner[i++])
                    value = v.toString()
                }
            }
            val key = attrName.toString().lowercase()
            if (key.isNotEmpty() && key !in attrs) attrs[key] = decodeReferences(value)
        }
        return ParsedTag(closing, selfClosing, name.toString().lowercase(), attrs)
    }

    private class OpenMention(val id: String, val label: String) {
        val text = StringBuilder()
    }

    /**
     * What a comment shows (section 9.5): text and mentions, line breaks for `<br>` and block ends,
     * everything else as its text. Mentions take the member's display name when the member is known.
     */
    fun decodeCommentHtml(html: String, members: List<CollabUser>): DecodedComment {
        val segments = mutableListOf<CommentSegment>()
        var plain = true
        var pendingBreak = false
        var wroteAny = false
        var mention: OpenMention? = null

        fun pushText(text: String) {
            if (text.isEmpty()) return
            val last = segments.lastOrNull()
            if (last is CommentSegment.Text) segments[segments.lastIndex] = CommentSegment.Text(last.text + text)
            else segments += CommentSegment.Text(text)
        }

        fun flush() {
            if (pendingBreak && wroteAny) pushText("\n")
            pendingBreak = false
        }

        fun appendText(raw: String) {
            val open = mention
            if (open != null) {
                open.text.append(raw)
                return
            }
            val text = raw.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ')
            if (text.isEmpty()) return
            if (pendingBreak && trimBoth(text).isEmpty()) return
            flush()
            pushText(text)
            wroteAny = true
        }

        fun finishMention() {
            val current = mention ?: return
            mention = null
            val id = current.id.trim()
            if (id.isEmpty()) {
                appendText(current.text.toString())
                return
            }
            val member = members.firstOrNull { it.username.lowercase() == id.lowercase() }
            val fromText = trimBoth(current.text.toString()).trimStart('@')
            val label = if (member != null) displayName(member) else current.label.trim().ifEmpty { fromText.ifEmpty { id } }
            flush()
            segments += CommentSegment.Mention(id, label)
            wroteAny = true
        }

        var i = 0
        val n = html.length
        while (i < n) {
            val ch = html[i]
            if (ch == '<') {
                if (html.startsWith("<!--", i)) {
                    val end = html.indexOf("-->", i + 4)
                    i = if (end == -1) n else end + 3
                    continue
                }
                val next = html.getOrNull(i + 1)
                if (next == '!') {
                    val end = html.indexOf('>', i)
                    i = if (end == -1) n else end + 1
                    continue
                }
                if (next == '/' || isAsciiLetter(next)) {
                    val end = tagEnd(html, i)
                    if (end == -1) {
                        appendText(decodeReferences(html.substring(i)))
                        break
                    }
                    val tag = parseTag(html.substring(i + 1, end))
                    i = end + 1
                    if (tag.name.isEmpty()) continue
                    if (tag.name !in PLAIN_TAGS) plain = false
                    if (!tag.closing && tag.name in SKIPPED_CONTENT) {
                        val close = html.lowercase().indexOf("</${tag.name}", i)
                        i = if (close == -1) {
                            n
                        } else {
                            val closeEnd = html.indexOf('>', close)
                            if (closeEnd == -1) n else closeEnd + 1
                        }
                        continue
                    }
                    if (tag.name == "mention-user") {
                        if (tag.closing) {
                            finishMention()
                        } else {
                            finishMention()
                            mention = OpenMention(tag.attrs["data-id"] ?: "", tag.attrs["data-label"] ?: "")
                            if (tag.selfClosing) finishMention()
                        }
                        continue
                    }
                    if (mention != null) continue
                    if (tag.name == "br") {
                        if (!tag.closing) {
                            flush()
                            pushText("\n")
                            wroteAny = true
                        }
                        continue
                    }
                    if (tag.closing && tag.name in BLOCK_TAGS) pendingBreak = true
                    continue
                }
            }
            var j = html.indexOf('<', if (ch == '<') i + 1 else i)
            if (j == -1) j = n
            appendText(decodeReferences(html.substring(i, j)))
            i = j
        }
        finishMention()

        // Trim the ends across segments: a text segment at either end loses its spaces and line breaks.
        val first = segments.firstOrNull()
        if (first is CommentSegment.Text) {
            val trimmed = trimStart(first.text)
            if (trimmed.isEmpty()) segments.removeAt(0) else segments[0] = CommentSegment.Text(trimmed)
        }
        val last = segments.lastOrNull()
        if (last is CommentSegment.Text) {
            val trimmed = trimEnd(last.text)
            if (trimmed.isEmpty()) segments.removeAt(segments.lastIndex) else segments[segments.lastIndex] = CommentSegment.Text(trimmed)
        }

        val editText = if (plain) {
            segments.joinToString("") { s ->
                when (s) {
                    is CommentSegment.Text -> s.text
                    is CommentSegment.Mention -> "@${s.mention}"
                }
            }
        } else {
            null
        }
        return DecodedComment(segments, plain, editText)
    }

    /** The comment as plain text, mentions as "@" and the label: for previews and search. */
    fun commentPlainText(html: String, members: List<CollabUser>): String =
        decodeCommentHtml(html, members).segments.joinToString("") { s ->
            when (s) {
                is CommentSegment.Text -> s.text
                is CommentSegment.Mention -> "@${s.label}"
            }
        }

    // --- Mention suggestions -----------------------------------------------------------------

    /** The mention being typed at the caret, or null (section 9.5). */
    fun mentionQueryAt(text: String, caret: Int): MentionQuery? {
        val before = text.substring(0, caret.coerceIn(0, text.length))
        val at = before.lastIndexOf('@')
        if (at == -1) return null
        val query = before.substring(at + 1)
        if (query.any { it.isWhitespace() }) return null
        if (at > 0 && !before[at - 1].isWhitespace()) return null
        return MentionQuery(query, at)
    }

    /** Members other than you whose username, display name or a word of it starts with the query. */
    fun <T> mentionSuggestions(query: String, members: List<T>, me: Long?, userOf: (T) -> CollabUser): List<T> {
        val q = query.lowercase()
        return members.filter { item ->
            val m = userOf(item)
            if (m.id == me) return@filter false
            if (m.username.lowercase().startsWith(q)) return@filter true
            val name = displayName(m).lowercase()
            name.startsWith(q) || name.split(WHITESPACE).any { it.startsWith(q) }
        }
    }

    fun mentionSuggestions(query: String, members: List<CollabUser>, me: Long?): List<CollabUser> =
        mentionSuggestions(query, members, me) { it }

    private val WHITESPACE = Regex("\\s+")

    /** Replace `@query` with `@username ` and put the caret after the space. */
    fun applyMention(text: String, caret: Int, mention: MentionQuery, username: String): MentionEdit {
        val insert = "@$username "
        val rest = text.substring(maxOf(caret, mention.start).coerceAtMost(text.length))
        return MentionEdit(text.substring(0, mention.start) + insert + rest, mention.start + insert.length)
    }

    // --- Edited --------------------------------------------------------------------------------

    private fun parseMillis(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching { Instant.parse(value).toEpochMilliseconds() }.getOrNull()
    }

    /** A comment is edited when `updated` is at least one second after `created` (both read to the millisecond). */
    fun isCommentEdited(created: String?, updated: String?): Boolean {
        val c = parseMillis(created) ?: return false
        val u = parseMillis(updated) ?: return false
        return u - c >= 1000
    }
}
