package com.rendyhd.vicu.util

import com.rendyhd.vicu.ai.AiText

/**
 * Extracts embedded links from task description HTML comments.
 * Mirrors the desktop app's `note-link.ts`.
 */
object TaskLinkParser {

    sealed class TaskLink {
        data class ObsidianNote(val url: String, val displayName: String) : TaskLink()
        data class BrowserPage(val url: String, val displayName: String) : TaskLink()
    }

    // The marks are put in as characters, not as \uD83D\uDCCE escapes inside the pattern: the
    // Kotlin/Native regex engine reads an escaped surrogate pair as two separate characters.
    private const val NOTE_MARK = "\uD83D\uDCCE" // 📎
    private const val PAGE_MARK = "\uD83D\uDD17" // 🔗

    private val noteLinkRegex = Regex("""<!-- notelink:(obsidian://[^">\s]+) -->""")
    private val noteLinkNameRegex = Regex(NOTE_MARK + """\s*([^<]+)</a>""")
    private val noteLinkCommentRegex = Regex("""<!-- notelink:obsidian://[^>]+ -->""")
    private val noteLinkAnchorRegex = Regex("""<p><a href="obsidian://[^"]*">""" + NOTE_MARK + """\s*[^<]*</a></p>""")

    private val pageLinkRegex = Regex("""<!-- pagelink:(https?://[^">\s]+) -->""")
    private val pageLinkNameRegex = Regex(PAGE_MARK + """\s*([^<]+)</a>""")
    private val pageLinkCommentRegex = Regex("""<!-- pagelink:https?://[^>]+ -->""")
    private val pageLinkAnchorRegex = Regex("""<p><a href="https?://[^"]*">""" + PAGE_MARK + """\s*[^<]*</a></p>""")

    fun extractLinks(description: String?): List<TaskLink> {
        if (description.isNullOrEmpty()) return emptyList()
        val links = mutableListOf<TaskLink>()

        val noteMatch = noteLinkRegex.find(description)
        if (noteMatch != null) {
            val url = unescapeHtml(noteMatch.groupValues[1])
            val nameMatch = noteLinkNameRegex.find(description)
            val name = nameMatch?.groupValues?.get(1)?.trim() ?: "Obsidian note"
            links += TaskLink.ObsidianNote(url, name)
        }

        val pageMatch = pageLinkRegex.find(description)
        if (pageMatch != null) {
            val url = unescapeHtml(pageMatch.groupValues[1])
            val titleMatch = pageLinkNameRegex.find(description)
            val title = titleMatch?.groupValues?.get(1)?.trim() ?: url
            links += TaskLink.BrowserPage(url, title)
        }

        return links
    }

    /** Strip all link HTML (comments + anchors) from a description for display. */
    fun stripLinks(description: String?): String {
        if (description.isNullOrEmpty()) return ""
        return description
            .replace(noteLinkCommentRegex, "")
            .replace(noteLinkAnchorRegex, "")
            .replace(pageLinkCommentRegex, "")
            .replace(pageLinkAnchorRegex, "")
            .trim()
    }

    /**
     * Returns true if the description contains user-visible notes content once embedded
     * Obsidian/page link HTML is stripped. Used to decide whether to show a "has notes"
     * glyph on the collapsed task row separately from the existing link icons.
     */
    fun hasNotesContent(description: String?): Boolean {
        if (description.isNullOrBlank()) return false
        val stripped = stripLinks(RoutineEnvelope.strip(description))
            .replace(Regex("<[^>]+>"), "")
            .replace(Regex("\\s|&nbsp;"), "")
        return stripped.isNotEmpty()
    }

    private val htmlCommentRegex = Regex("""<!--[\s\S]*?-->""")
    private val imageTokenRegex = Regex("""\[\[image(-pending)?:[^\]]*]]""")
    private val lineBreakRegex = Regex("""(?i)<br\s*/?>|</(p|li|div|h[1-6]|blockquote|pre)>""")
    private val tagRegex = Regex("""<[^>]+>""")
    private val whitespaceRegex = Regex("""\s+""")

    /**
     * The notes as one line of plain text, for a Comfortable row (design-system section 4): no link
     * markers and images, line breaks as spaces. Empty when no text is left. The same as the
     * desktop's `notesPreview`.
     */
    fun notesPreview(description: String?): String {
        if (description.isNullOrBlank()) return ""
        val text = stripLinks(RoutineEnvelope.strip(description))
            .replace(htmlCommentRegex, " ")
            .replace(imageTokenRegex, " ")
            .replace(lineBreakRegex, " ")
            .replace(tagRegex, "")
        return AiText.decodeEntities(text).replace(whitespaceRegex, " ").trim()
    }

    /** Extract the raw link HTML portion from a description (for re-appending on save). */
    fun extractLinkHtml(description: String?): String {
        if (description.isNullOrEmpty()) return ""
        val noteComment = noteLinkCommentRegex.find(description)?.value ?: ""
        val noteAnchor = noteLinkAnchorRegex.find(description)?.value ?: ""
        val pageComment = pageLinkCommentRegex.find(description)?.value ?: ""
        val pageAnchor = pageLinkAnchorRegex.find(description)?.value ?: ""
        return noteComment + noteAnchor + pageComment + pageAnchor
    }

    private fun unescapeHtml(str: String): String =
        str.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
}
