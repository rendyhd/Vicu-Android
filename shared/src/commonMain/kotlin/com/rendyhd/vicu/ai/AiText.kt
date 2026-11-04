package com.rendyhd.vicu.ai

import com.rendyhd.vicu.util.DescriptionHtml

/**
 * Plain text in and out of task descriptions for the assistant: notes from a model become the
 * description by `description-format-v1` (paragraphs, line breaks), and a description is read back
 * as plain text without its metadata envelope.
 */
object AiText {
    /** Plain notes as the canonical HTML body: a `p` per paragraph, `br` for a line break inside one. */
    fun notesToHtml(notes: String): String {
        val text = notes.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (text.isEmpty()) return ""
        return text.split(Regex("\n\\s*\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("") { paragraph -> "<p>" + paragraph.split('\n').joinToString("<br>") { escape(it.trim()) } + "</p>" }
    }

    /**
     * [description] with [notes] added as new paragraphs at the end of its body; the rest of the
     * description (images, links, metadata) is kept as it was (`docs/ai-assistant-v1.md`, section 5.4).
     */
    fun appendNotes(description: String, notes: String): String {
        val html = notesToHtml(notes)
        if (html.isEmpty()) return description
        val split = DescriptionHtml.splitForEditor(description)
        return DescriptionHtml.merge(split.htmlBody + html, split.imageRefs, split.linkHtml, split.routineHtml)
    }

    /** The description's body as plain text: paragraphs and list items on lines of their own. */
    fun htmlToPlain(description: String?): String {
        if (description.isNullOrBlank()) return ""
        val body = DescriptionHtml.splitForEditor(description).htmlBody
        var text = body
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|h[1-6]|blockquote|pre)>"), "\n\n")
            .replace(Regex("(?i)<li[^>]*data-checked=\"true\"[^>]*>"), "\n[x] ")
            .replace(Regex("(?i)<li[^>]*data-checked=\"false\"[^>]*>"), "\n[ ] ")
            .replace(Regex("(?i)<li[^>]*>"), "\n- ")
            .replace(Regex("<[^>]+>"), "")
        text = decodeEntities(text)
        return text.lines().joinToString("\n") { it.trimEnd() }.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    fun hasNotes(description: String?): Boolean = htmlToPlain(description).isNotEmpty()

    fun escape(text: String): String = buildString {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(c)
            }
        }
    }

    internal fun decodeEntities(text: String): String =
        text.replace(Regex("&#(x?[0-9a-fA-F]+);")) { m ->
            val v = m.groupValues[1]
            val code = if (v.startsWith("x") || v.startsWith("X")) v.substring(1).toIntOrNull(16) else v.toIntOrNull()
            code?.takeIf { it in 1..0xFFFF }?.toChar()?.toString() ?: m.value
        }
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
}
