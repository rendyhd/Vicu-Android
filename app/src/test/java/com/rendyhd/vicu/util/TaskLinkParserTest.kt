package com.rendyhd.vicu.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskLinkParserTest {

    @Test
    fun `hasNotesContent returns false for null`() {
        assertFalse(TaskLinkParser.hasNotesContent(null))
    }

    @Test
    fun `hasNotesContent returns false for empty`() {
        assertFalse(TaskLinkParser.hasNotesContent(""))
    }

    @Test
    fun `hasNotesContent returns false for blank whitespace`() {
        assertFalse(TaskLinkParser.hasNotesContent("   \n  "))
    }

    @Test
    fun `hasNotesContent returns false for description that is only an Obsidian note link`() {
        val onlyLink =
            """<!-- notelink:obsidian://open?vault=Personal&file=Tasks/X --><p><a href="obsidian://open?vault=Personal&file=Tasks/X">""" +
                "📎 Tasks/X</a></p>"
        assertFalse(TaskLinkParser.hasNotesContent(onlyLink))
    }

    @Test
    fun `hasNotesContent returns false for description that is only a page link`() {
        val onlyLink =
            """<!-- pagelink:https://example.com --><p><a href="https://example.com">""" +
                "🔗 example.com</a></p>"
        assertFalse(TaskLinkParser.hasNotesContent(onlyLink))
    }

    @Test
    fun `hasNotesContent returns true for plain text`() {
        assertTrue(TaskLinkParser.hasNotesContent("Buy milk and bread"))
    }

    @Test
    fun `hasNotesContent returns true for plain text plus an embedded link`() {
        val mixed = "Buy milk and bread\n<!-- pagelink:https://recipes.example --><p><a href=\"https://recipes.example\">🔗 recipes</a></p>"
        assertTrue(TaskLinkParser.hasNotesContent(mixed))
    }
}
