package com.rendyhd.vicu.util

import com.rendyhd.vicu.data.local.RowDensity
import com.rendyhd.vicu.ui.theme.DesignTokensFixture
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The same cases as the desktop's `row-density.test.ts`. */
class NotesPreviewTest {

    @Test
    fun theDensitiesHaveTheValuesOfTheFixture() {
        val tokens = DesignTokensFixture.root.getValue("rowDensity").jsonObject
        assertEquals("compact", tokens.getValue("default").jsonPrimitive.content)
        for (density in RowDensity.entries) {
            val values = tokens.getValue(density.name.lowercase()).jsonObject
            assertEquals(values.getValue("titleLines").jsonPrimitive.int, density.titleLines, density.name)
            assertEquals(values.getValue("notesLines").jsonPrimitive.int, density.notesLines, density.name)
        }
    }

    @Test
    fun isThePlainTextOnOneLine() {
        assertEquals("Call the bank Ask for Sam", TaskLinkParser.notesPreview("<p>Call the <b>bank</b></p><p>Ask for Sam</p>"))
        assertEquals("One Two", TaskLinkParser.notesPreview("<ul><li>One</li><li>Two</li></ul>"))
        assertEquals("First second third", TaskLinkParser.notesPreview("<p>First<br>second<br/>third</p>"))
    }

    @Test
    fun decodesEntitiesOnce() {
        assertEquals("Salt & pepper <3 'a' A", TaskLinkParser.notesPreview("<p>Salt &amp; pepper&nbsp;&lt;3 &#39;a&#39; &#x41;</p>"))
        assertEquals("&lt;", TaskLinkParser.notesPreview("<p>&amp;lt;</p>"))
    }

    @Test
    fun leavesOutLinkMarkersAndImages() {
        val note = "<!-- notelink:obsidian://open?file=a --><p><a href=\"obsidian://open?file=a\">📎 A note</a></p>"
        val page = "<!-- pagelink:https://example.com --><p><a href=\"https://example.com\">🔗 A page</a></p>"
        assertEquals("Text", TaskLinkParser.notesPreview("<p>Text</p>$note$page"))
        assertEquals("", TaskLinkParser.notesPreview(note))
        assertEquals("after", TaskLinkParser.notesPreview("<p>[[image:12]] after [[image-pending:ab-1]]</p>"))
        assertEquals("", TaskLinkParser.notesPreview("<img src=\"a.png\">"))
    }

    @Test
    fun leavesTheIconToNotesWithoutText() {
        val onlyImage = "<p>[[image:12]]</p>"
        assertTrue(TaskLinkParser.hasNotesContent(onlyImage))
        assertEquals("", TaskLinkParser.notesPreview(onlyImage))
    }

    @Test
    fun isEmptyWithoutNotes() {
        assertEquals("", TaskLinkParser.notesPreview(null))
        assertEquals("", TaskLinkParser.notesPreview(""))
        assertEquals("", TaskLinkParser.notesPreview("<p></p>"))
    }
}
