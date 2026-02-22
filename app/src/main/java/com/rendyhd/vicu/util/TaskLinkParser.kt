package com.rendyhd.vicu.util

/**
 * Extracts embedded links from task description HTML comments.
 * Mirrors the desktop app's `note-link.ts`.
 */
object TaskLinkParser {

    sealed class TaskLink {
        data class ObsidianNote(val url: String, val displayName: String) : TaskLink()
        data class BrowserPage(val url: String, val displayName: String) : TaskLink()
    }

    private val noteLinkRegex = Regex("""<!-- notelink:(obsidian://[^">\s]+) -->""")
    private val noteLinkNameRegex = Regex("""\uD83D\uDCCE\s*([^<]+)</a>""")
    private val noteLinkCommentRegex = Regex("""<!-- notelink:obsidian://[^>]+ -->""")
    private val noteLinkAnchorRegex = Regex("""<p><a href="obsidian://[^"]*">\uD83D\uDCCE\s*[^<]*</a></p>""")

    private val pageLinkRegex = Regex("""<!-- pagelink:(https?://[^">\s]+) -->""")
    private val pageLinkNameRegex = Regex("""\uD83D\uDD17\s*([^<]+)</a>""")
    private val pageLinkCommentRegex = Regex("""<!-- pagelink:https?://[^>]+ -->""")
    private val pageLinkAnchorRegex = Regex("""<p><a href="https?://[^"]*">\uD83D\uDD17\s*[^<]*</a></p>""")

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
