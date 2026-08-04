package com.rendyhd.vicu.util

import kotlin.test.Test
import kotlin.test.assertEquals

class DescriptionHtmlTest {
    @Test
    fun formattedBodyImagesAndEmbeddedLinksRoundTripThroughEditorEnvelope() {
        val body = "<p><strong>Field</strong> notes</p>"
        val refs = listOf(
            ImageTokens.ImageRef.Image(41L),
            ImageTokens.ImageRef.Pending("pending-7"),
        )
        val links = """<!-- pagelink:https://example.com --><p><a href="https://example.com">🔗 Intel</a></p>"""
        val raw = DescriptionHtml.merge(body, refs, links)

        val split = DescriptionHtml.splitForEditor(raw)

        assertEquals(body, split.htmlBody)
        assertEquals(refs, split.imageRefs)
        assertEquals(links, split.linkHtml)
        assertEquals(raw, DescriptionHtml.merge(split.htmlBody, split.imageRefs, split.linkHtml))
    }

    @Test
    fun replacingRichHtmlDoesNotAlterImageOrLinkMetadata() {
        val refs = listOf(ImageTokens.ImageRef.Image(9L))
        val links = """<!-- notelink:obsidian://open?vault=Ops --><p><a href="obsidian://open?vault=Ops">📎 Runbook</a></p>"""
        val original = DescriptionHtml.merge("<p>Old</p>", refs, links)
        val split = DescriptionHtml.splitForEditor(original)

        val updated = DescriptionHtml.merge(
            htmlBody = "<p><em>Updated</em></p>",
            imageRefs = split.imageRefs,
            linkHtml = split.linkHtml,
        )
        val updatedSplit = DescriptionHtml.splitForEditor(updated)

        assertEquals("<p><em>Updated</em></p>", updatedSplit.htmlBody)
        assertEquals(refs, updatedSplit.imageRefs)
        assertEquals(links, updatedSplit.linkHtml)
    }
}
