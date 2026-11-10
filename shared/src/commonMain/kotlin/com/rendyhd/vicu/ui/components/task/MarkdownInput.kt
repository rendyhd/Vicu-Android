package com.rendyhd.vicu.ui.components.task

/**
 * Markdown input (docs/description-format-v1.md, "Markdown input"; vectors `markdown` in
 * test-fixtures/description-format-v1.json, the same as the desktop's `lib/markdown-input.ts`).
 *
 * Markdown is only a way of writing notes: pasted text that uses it becomes the description HTML
 * the editors store. Nothing is ever stored as Markdown and stored notes are never read as it.
 * This is a line-for-line port of the desktop code, so both apps give the same HTML: keep them in
 * step. "Whitespace" is JavaScript's `\s` and `trim` ([isMarkdownSpace]), not Kotlin's.
 */
internal object MarkdownInput {

    data class Result(
        /** Description HTML: top-level blocks joined by a newline; "" for blank input. */
        val html: String,
        /** True when the text used Markdown syntax (a bare URL alone does not count). */
        val markdown: Boolean,
    )

    private const val PUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

    fun escapeText(text: String): String = buildString(text.length) {
        for (ch in text) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(ch)
            }
        }
    }

    /** JavaScript's `\s` (and what `String.prototype.trim` removes). */
    fun isMarkdownSpace(ch: Char): Boolean =
        ch == ' ' || ch == '\t' || ch == '\n' || ch == '\u000B' || ch == '\u000C' || ch == '\r' ||
            ch == ' ' || ch == ' ' || ch in ' '..' ' || ch == ' ' ||
            ch == ' ' || ch == ' ' || ch == ' ' || ch == '　' || ch == '﻿'

    private fun String.jsTrim(): String = trim(::isMarkdownSpace)

    private fun isSpace(ch: Char?): Boolean = ch == null || isMarkdownSpace(ch)

    /** JavaScript's `[\p{L}\p{N}]` on one UTF-16 unit. */
    private fun isWordChar(ch: Char?): Boolean = ch != null && (
        ch.isLetter() || ch.category == CharCategory.DECIMAL_DIGIT_NUMBER ||
            ch.category == CharCategory.LETTER_NUMBER || ch.category == CharCategory.OTHER_NUMBER
        )

    /** A link destination Markdown input may use: http(s) with something after `//`, or mailto. */
    fun markdownHref(raw: String): String? {
        val value = raw.jsTrim()
        val rest = when {
            value.startsWith("https://", ignoreCase = true) -> value.substring(8)
            value.startsWith("http://", ignoreCase = true) -> value.substring(7)
            value.startsWith("mailto:", ignoreCase = true) -> value.substring(7)
            else -> return null
        }
        if (rest.isEmpty() || rest.any { isMarkdownSpace(it) || it.code < 0x20 || it.code == 0x7f }) return null
        return value
    }

    private fun runLength(text: String, at: Int, ch: Char): Int {
        var end = at
        while (end < text.length && text[end] == ch) end++
        return end - at
    }

    /** Where the code span opened by the backtick run at [at] ends (index after its closing run), or -1. */
    private fun codeSpanEnd(text: String, at: Int): Int {
        val n = runLength(text, at, '`')
        var i = at + n
        while (i < text.length) {
            if (text[i] == '`') {
                val m = runLength(text, i, '`')
                if (m == n) return i + m
                i += m
            } else {
                i++
            }
        }
        return -1
    }

    private class Link(val end: Int, val text: String, val href: String, val title: String?)

    /** A `[text](destination "title")` starting at [at], or null. */
    private fun parseLink(text: String, at: Int): Link? {
        if (text.getOrNull(at) != '[') return null
        var depth = 0
        var close = -1
        var i = at
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\\' && i + 1 < text.length) {
                i += 2
                continue
            }
            if (ch == '`') {
                val end = codeSpanEnd(text, i)
                if (end > 0) {
                    i = end
                    continue
                }
            }
            if (ch == '[') {
                depth++
            } else if (ch == ']') {
                depth--
                if (depth == 0) {
                    close = i
                    break
                }
            }
            i++
        }
        if (close < 0 || text.getOrNull(close + 1) != '(') return null
        i = close + 2
        while (text.getOrNull(i) == ' ' || text.getOrNull(i) == '\t') i++
        val destination: String
        if (text.getOrNull(i) == '<') {
            val end = text.indexOf('>', i + 1)
            if (end < 0) return null
            destination = text.substring(i + 1, end)
            i = end + 1
        } else {
            var parens = 0
            val start = i
            while (i < text.length && !isMarkdownSpace(text[i])) {
                if (text[i] == '(') {
                    parens++
                } else if (text[i] == ')') {
                    if (parens == 0) break
                    parens--
                }
                i++
            }
            destination = text.substring(start, i)
        }
        var title: String? = null
        val beforeTitle = i
        while (text.getOrNull(i) == ' ' || text.getOrNull(i) == '\t') i++
        if (i > beforeTitle && (text.getOrNull(i) == '"' || text.getOrNull(i) == '\'')) {
            val quote = text[i]
            val end = text.indexOf(quote, i + 1)
            if (end < 0) return null
            title = text.substring(i + 1, end)
            i = end + 1
            while (text.getOrNull(i) == ' ' || text.getOrNull(i) == '\t') i++
        }
        if (text.getOrNull(i) != ')') return null
        val href = markdownHref(destination) ?: return null
        return Link(end = i + 1, text = text.substring(at + 1, close), href = href, title = title)
    }

    /** A bare http(s) URL at [at], as end and href, or null. */
    private fun bareUrl(text: String, at: Int): Pair<Int, String>? {
        val scheme = when {
            text.startsWith("https://", at, ignoreCase = true) -> 8
            text.startsWith("http://", at, ignoreCase = true) -> 7
            else -> return null
        }
        var end = at + scheme
        while (end < text.length && !isMarkdownSpace(text[end]) && text[end] != '<') end++
        if (end == at + scheme) return null
        var url = text.substring(at, end)
        while (true) {
            val last = url.last()
            url = when {
                last in ".,:;!?'\"*_~" -> url.dropLast(1)
                last == ')' && url.count { it == ')' } > url.count { it == '(' } -> url.dropLast(1)
                else -> break
            }
        }
        val href = markdownHref(url) ?: return null
        return if (url.length > url.indexOf("//") + 2) Pair(at + url.length, href) else null
    }

    private class Closer(val contentEnd: Int, val end: Int)

    /** See the desktop's `findCloser` and the contract's inline rule 8. */
    private fun findCloser(text: String, from: Int, ch: Char, need: Int): Closer? {
        var i = from
        while (i < text.length) {
            val c = text[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (c == '`') {
                val end = codeSpanEnd(text, i)
                i = if (end > 0) end else i + runLength(text, i, '`')
                continue
            }
            if (c != ch) {
                i++
                continue
            }
            val m = runLength(text, i, ch)
            val runEnd = i + m
            val fits = if (need == 1) m == 1 || m >= 3 else m >= need
            val rightFlanking = !isSpace(text.getOrNull(i - 1)) && (ch != '_' || !isWordChar(text.getOrNull(runEnd)))
            if (fits && rightFlanking && i > from) return Closer(contentEnd = runEnd - need, end = runEnd)
            i = runEnd
        }
        return null
    }

    private fun findStrikeCloser(text: String, from: Int): Closer? {
        var i = from
        while (i < text.length) {
            if (text[i] == '\\') {
                i += 2
                continue
            }
            if (text[i] == '`') {
                val end = codeSpanEnd(text, i)
                i = if (end > 0) end else i + runLength(text, i, '`')
                continue
            }
            if (text[i] == '~') {
                val m = runLength(text, i, '~')
                if (m == 2 && i > from && !isSpace(text.getOrNull(i - 1))) return Closer(contentEnd = i, end = i + 2)
                i += m
                continue
            }
            i++
        }
        return null
    }

    private class State {
        var markdown = false
    }

    private fun inline(text: String, state: State, inLink: Boolean): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val ch = text[i]

            if (ch == '\\' && i + 1 < text.length && text[i + 1] in PUNCTUATION) {
                out.append(escapeText(text[i + 1].toString()))
                state.markdown = true
                i += 2
                continue
            }

            if (ch == '`') {
                val n = runLength(text, i, '`')
                val end = codeSpanEnd(text, i)
                if (end < 0) {
                    out.append(escapeText(text.substring(i, i + n)))
                    i += n
                    continue
                }
                var code = text.substring(i + n, end - n)
                if (code.length >= 2 && code.startsWith(' ') && code.endsWith(' ') && code.jsTrim().isNotEmpty()) {
                    code = code.substring(1, code.length - 1)
                }
                out.append("<code>").append(escapeText(code)).append("</code>")
                state.markdown = true
                i = end
                continue
            }

            if (ch == '!' && text.getOrNull(i + 1) == '[' && !inLink) {
                val image = parseLink(text, i + 1)
                if (image != null) {
                    // An image is not part of the format: it stays as its text.
                    out.append(escapeText(text.substring(i, image.end)))
                    i = image.end
                    continue
                }
            }

            if (ch == '[' && !inLink) {
                val link = parseLink(text, i)
                if (link != null) {
                    val title = link.title?.let { " title=\"${escapeText(it)}\"" } ?: ""
                    out.append("<a href=\"").append(escapeText(link.href)).append('"').append(title).append('>')
                        .append(inline(link.text, state, true)).append("</a>")
                    state.markdown = true
                    i = link.end
                    continue
                }
            }

            if (ch == '<' && !inLink) {
                val end = text.indexOf('>', i + 1)
                val inside = if (end > 0) text.substring(i + 1, end) else null
                val href = inside?.let(::markdownHref)
                if (href != null && inside.none(::isMarkdownSpace)) {
                    out.append("<a href=\"").append(escapeText(href)).append("\">").append(escapeText(inside)).append("</a>")
                    state.markdown = true
                    i = end + 1
                    continue
                }
            }

            if ((ch == 'h' || ch == 'H') && !inLink && (i == 0 || isMarkdownSpace(text[i - 1]) || text[i - 1] == '(')) {
                val url = bareUrl(text, i)
                if (url != null) {
                    out.append("<a href=\"").append(escapeText(url.second)).append("\">")
                        .append(escapeText(text.substring(i, url.first))).append("</a>")
                    i = url.first
                    continue
                }
            }

            if (ch == '~' && runLength(text, i, '~') == 2 && !isSpace(text.getOrNull(i + 2))) {
                val closer = findStrikeCloser(text, i + 2)
                if (closer != null) {
                    out.append("<s>").append(inline(text.substring(i + 2, closer.contentEnd), state, inLink)).append("</s>")
                    state.markdown = true
                    i = closer.end
                    continue
                }
            }

            if (ch == '*' || ch == '_') {
                val n = runLength(text, i, ch)
                val leftFlanking = !isSpace(text.getOrNull(i + n)) && (ch != '_' || !isWordChar(text.getOrNull(i - 1)))
                if (leftFlanking) {
                    val emphasis = emphasisAt(text, i, ch, minOf(n, 3), n, state, inLink)
                    if (emphasis != null) {
                        out.append(emphasis.first)
                        i = emphasis.second
                        continue
                    }
                }
                out.append(escapeText(text.substring(i, i + n)))
                i += n
                continue
            }

            out.append(escapeText(ch.toString()))
            i++
        }
        return out.toString()
    }

    private fun emphasisAt(
        text: String,
        at: Int,
        ch: Char,
        k: Int,
        n: Int,
        state: State,
        inLink: Boolean,
    ): Pair<String, Int>? {
        for (need in k downTo 1) {
            val contentStart = at + n
            val closer = findCloser(text, contentStart, ch, need) ?: continue
            if (closer.contentEnd <= contentStart) continue
            val lead = escapeText(text.substring(at, at + n - need))
            val inner = inline(text.substring(contentStart, closer.contentEnd), state, inLink)
            state.markdown = true
            val html = when (need) {
                3 -> "<em><strong>$inner</strong></em>"
                2 -> "<strong>$inner</strong>"
                else -> "<em>$inner</em>"
            }
            return Pair(lead + html, closer.end)
        }
        return null
    }

    // ---- Blocks -------------------------------------------------------------------------------

    private enum class ListKind { Bullet, Ordered, Task }

    private class ListItem(val paragraphs: MutableList<String>, val checked: Boolean, val lists: MutableList<MdList> = mutableListOf())

    private class MdList(val kind: ListKind, val indent: Int, val items: MutableList<ListItem>)

    private val FENCE = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
    private val RULE = Regex("^ {0,3}(?:(?:\\*[ \\t]*){3,}|(?:-[ \\t]*){3,}|(?:_[ \\t]*){3,})$")
    private val HEADING = Regex("^ {0,3}(#{1,6})[ \\t]+(.*?)(?:[ \\t]+#+)?[ \\t]*$")
    private val QUOTE = Regex("^ {0,3}>[ \\t]?(.*)$")
    private val ITEM = Regex("^([ \\t]*)([-*+]|[0-9]{1,9}[.)])[ \\t]+(.*)$")
    private val TASK = Regex("^\\[([ xX])\\](?:[ \\t]+(.*))?$")

    /** Leading whitespace in columns, a tab counting four. */
    private fun indentOf(line: String): Int {
        var columns = 0
        for (ch in line) {
            when (ch) {
                ' ' -> columns++
                '\t' -> columns += 4
                else -> return columns
            }
        }
        return columns
    }

    private fun isBlank(line: String): Boolean = line.all(::isMarkdownSpace)

    private fun listHtml(list: MdList, state: State): String {
        val items = list.items.joinToString("") { item ->
            val body = item.paragraphs.joinToString("") { "<p>${inline(it, state, false)}</p>" }
            val nested = item.lists.joinToString("") { listHtml(it, state) }
            if (list.kind == ListKind.Task) {
                val box = if (item.checked) "<input type=\"checkbox\" checked=\"checked\">" else "<input type=\"checkbox\">"
                "<li data-type=\"taskItem\" data-checked=\"${item.checked}\"><label contenteditable=\"false\">$box<span></span></label><div>$body$nested</div></li>"
            } else {
                "<li>$body$nested</li>"
            }
        }
        return when (list.kind) {
            ListKind.Task -> "<ul data-type=\"taskList\">$items</ul>"
            ListKind.Ordered -> "<ol>$items</ol>"
            ListKind.Bullet -> "<ul>$items</ul>"
        }
    }

    /** Markdown text as description HTML (see the object comment for where the rules live). */
    fun toDescriptionHtml(source: String): Result {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val state = State()
        val blocks = mutableListOf<String>()

        /** Open lists, outermost first; the first is a top-level list. */
        var stack = mutableListOf<MdList>()
        var quote: MutableList<String>? = null

        fun closeList() {
            if (stack.isNotEmpty()) blocks += listHtml(stack[0], state)
            stack = mutableListOf()
        }
        fun closeQuote() {
            val open = quote
            if (!open.isNullOrEmpty()) {
                blocks += "<blockquote>" + open.joinToString("") { "<p>${inline(it, state, false)}</p>" } + "</blockquote>"
            }
            quote = null
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]

            if (isBlank(line)) {
                closeQuote()
                // A blank line ends a list unless the next text line is an item or belongs to the last one.
                var next = i + 1
                while (next < lines.size && isBlank(lines[next])) next++
                val following = lines.getOrNull(next)
                val keepsList = following != null &&
                    stack.isNotEmpty() &&
                    !RULE.matches(following) &&
                    (ITEM.matches(following) || indentOf(following) > stack[0].indent)
                if (!keepsList) closeList()
                i = next
                continue
            }

            val item = if (!RULE.matches(line)) ITEM.matchEntire(line) else null
            if (item != null) {
                closeQuote()
                val indent = indentOf(item.groupValues[1])
                var content = item.groupValues[3]
                var kind = if (item.groupValues[2][0].isDigit()) ListKind.Ordered else ListKind.Bullet
                var checked = false
                val task = if (kind == ListKind.Bullet) TASK.matchEntire(content) else null
                if (task != null) {
                    kind = ListKind.Task
                    checked = task.groupValues[1] != " "
                    content = task.groupValues[2]
                }
                state.markdown = true
                val entry = ListItem(paragraphs = mutableListOf(content.jsTrim()), checked = checked)

                while (stack.size > 1 && indent < stack.last().indent) stack.removeAt(stack.lastIndex)
                val top = stack.lastOrNull()
                when {
                    top == null -> stack = mutableListOf(MdList(kind, indent, mutableListOf(entry)))
                    indent > top.indent -> {
                        val child = MdList(kind, indent, mutableListOf(entry))
                        top.items.last().lists += child
                        stack += child
                    }
                    top.kind == kind -> top.items += entry
                    stack.size == 1 -> {
                        closeList()
                        stack = mutableListOf(MdList(kind, indent, mutableListOf(entry)))
                    }
                    else -> {
                        stack.removeAt(stack.lastIndex)
                        val sibling = MdList(kind, indent, mutableListOf(entry))
                        stack.last().items.last().lists += sibling
                        stack += sibling
                    }
                }
                i++
                continue
            }

            if (stack.isNotEmpty() && indentOf(line) > stack[0].indent) {
                // More indented than the top-level items: text of the nearest item it is indented under.
                val indent = indentOf(line)
                while (stack.size > 1 && indent <= stack.last().indent) stack.removeAt(stack.lastIndex)
                stack.last().items.last().paragraphs += line.jsTrim()
                i++
                continue
            }

            val fence = FENCE.matchEntire(line)
            if (fence != null && !(fence.groupValues[1][0] == '`' && '`' in fence.groupValues[2])) {
                closeList()
                closeQuote()
                val marker = fence.groupValues[1]
                val indent = indentOf(line)
                val closing = Regex("^ {0,3}${if (marker[0] == '`') "`" else "~"}{${marker.length},}[ \\t]*$")
                val code = mutableListOf<String>()
                i++
                while (i < lines.size) {
                    if (closing.matches(lines[i])) {
                        i++
                        break
                    }
                    var text = lines[i]
                    var strip = indent
                    while (strip > 0 && text.startsWith(' ')) {
                        text = text.substring(1)
                        strip--
                    }
                    code += text
                    i++
                }
                blocks += "<pre><code>${escapeText(code.joinToString("\n"))}</code></pre>"
                state.markdown = true
                continue
            }

            if (RULE.matches(line)) {
                closeList()
                closeQuote()
                blocks += "<hr>"
                state.markdown = true
                i++
                continue
            }

            val heading = HEADING.matchEntire(line)
            if (heading != null && heading.groupValues[2].jsTrim().isNotEmpty()) {
                closeList()
                closeQuote()
                val level = heading.groupValues[1].length
                blocks += "<h$level>${inline(heading.groupValues[2].jsTrim(), state, false)}</h$level>"
                state.markdown = true
                i++
                continue
            }

            val quoted = QUOTE.matchEntire(line)
            if (quoted != null) {
                closeList()
                state.markdown = true
                val open = quote ?: mutableListOf<String>().also { quote = it }
                if (quoted.groupValues[1].jsTrim().isNotEmpty()) open += quoted.groupValues[1].jsTrim()
                i++
                continue
            }

            closeList()
            closeQuote()
            blocks += "<p>${inline(line.jsTrim(), state, false)}</p>"
            i++
        }
        closeList()
        closeQuote()

        return Result(html = blocks.joinToString("\n"), markdown = state.markdown)
    }
}
