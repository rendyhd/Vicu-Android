package com.rendyhd.vicu.util

/**
 * Splits a Vikunja task description into three pieces so the rich-text editor
 * only sees the HTML body:
 *   - html body (what TipTap / richeditor owns)
 *   - image token refs ([[image:N]], [[image-pending:uuid]])
 *   - preserved note/page-link HTML comments + anchors
 *
 * Inverse [merge] rejoins them in the same order Vikunja stored them.
 */
object DescriptionHtml {

    data class Split(
        val htmlBody: String,
        val imageRefs: List<ImageTokens.ImageRef>,
        val linkHtml: String,
    )

    fun splitForEditor(raw: String?): Split {
        if (raw.isNullOrEmpty()) return Split("", emptyList(), "")
        val linkHtml = TaskLinkParser.extractLinkHtml(raw)
        val withoutLinks = TaskLinkParser.stripLinks(raw)
        val (body, refs) = ImageTokens.parseValue(withoutLinks)
        return Split(body, refs, linkHtml)
    }

    fun merge(htmlBody: String, imageRefs: List<ImageTokens.ImageRef>, linkHtml: String): String {
        val withImages = ImageTokens.buildValue(htmlBody, imageRefs)
        if (linkHtml.isEmpty()) return withImages
        if (withImages.isEmpty()) return linkHtml
        return withImages + "\n" + linkHtml
    }
}
