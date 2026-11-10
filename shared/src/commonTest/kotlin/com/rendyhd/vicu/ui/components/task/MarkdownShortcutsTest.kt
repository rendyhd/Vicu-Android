package com.rendyhd.vicu.ui.components.task

import io.github.linreal.cascade.editor.core.BlockType
import io.github.linreal.cascade.editor.core.SpanStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The typing shortcuts of description format "Markdown input" (the desktop's TipTap rules). */
class MarkdownShortcutsTest {

    /** Types the last character of [text] with the cursor after it. */
    private fun typed(text: String, type: BlockType = BlockType.Paragraph) =
        MarkdownShortcuts.afterTyping(type, text, text.length, text.last())

    @Test
    fun blockMarkersAtTheStartOfAParagraph() {
        assertEquals(MarkdownShortcuts.Convert(BlockType.Heading(1), 2), typed("# "))
        assertEquals(MarkdownShortcuts.Convert(BlockType.Heading(6), 7), typed("###### "))
        assertNull(typed("####### "))
        assertEquals(MarkdownShortcuts.Convert(BlockType.Quote, 2), typed("> "))
        assertEquals(MarkdownShortcuts.Convert(BlockType.BulletList, 2), typed("* "))
        assertEquals(MarkdownShortcuts.Convert(BlockType.BulletList, 2), typed("+ "))
        assertEquals(MarkdownShortcuts.Convert(BlockType.NumberedList(3), 3), typed("3) "))
        assertEquals(MarkdownShortcuts.Convert(BlockType.Todo(false), 4), typed("[ ] "))
        assertEquals(MarkdownShortcuts.Convert(BlockType.Todo(true), 4), typed("[x] "))
        assertEquals(MarkdownShortcuts.Convert(BlockType.Code, 4), typed("``` "))
        assertEquals(MarkdownShortcuts.Convert(BlockType.Code, 6), typed("~~~kt "))
        assertEquals(MarkdownShortcuts.Divider, typed("---"))
        assertEquals(MarkdownShortcuts.Divider, typed("*** "))
    }

    @Test
    fun cascadeKeepsItsOwnListTriggers() {
        assertNull(typed("- "))
        assertNull(typed("1. "))
    }

    @Test
    fun aMarkerBeforeExistingTextStillCounts() {
        assertEquals(
            MarkdownShortcuts.Convert(BlockType.Heading(2), 3),
            MarkdownShortcuts.afterTyping(BlockType.Paragraph, "## Title", 3, ' '),
        )
        assertNull(MarkdownShortcuts.afterTyping(BlockType.Paragraph, "---x", 3, '-'))
    }

    @Test
    fun aChecklistFromABulletItem() {
        assertEquals(MarkdownShortcuts.Convert(BlockType.Todo(false), 4), typed("[ ] ", BlockType.BulletList))
        assertNull(typed("# ", BlockType.BulletList))
        assertNull(typed("> ", BlockType.Heading(1)))
    }

    @Test
    fun inlineMarkers() {
        assertEquals(MarkdownShortcuts.Inline(4, 12, "bold", SpanStyle.Bold), typed("Say **bold**"))
        assertEquals(MarkdownShortcuts.Inline(0, 8, "bold", SpanStyle.Bold), typed("__bold__"))
        assertEquals(MarkdownShortcuts.Inline(2, 6, "it", SpanStyle.Italic), typed("a *it*"))
        assertEquals(MarkdownShortcuts.Inline(2, 6, "it", SpanStyle.Italic), typed("a _it_"))
        assertEquals(MarkdownShortcuts.Inline(0, 8, "gone", SpanStyle.StrikeThrough), typed("~~gone~~"))
        assertEquals(MarkdownShortcuts.Inline(3, 9, "code", SpanStyle.InlineCode), typed("say`code`"))
        assertEquals(
            MarkdownShortcuts.Inline(3, 32, "a link", SpanStyle.Link("https://example.com")),
            typed("Go [a link](https://example.com)"),
        )
        assertEquals(MarkdownShortcuts.Inline(0, 8, "bold", SpanStyle.Bold), typed("**bold**", BlockType.Heading(2)))
        // As on the desktop (TipTap's rule): spaces inside the markers do not stop it.
        assertEquals(MarkdownShortcuts.Inline(2, 7, " 3 ", SpanStyle.Italic), typed("2 * 3 *"))
    }

    @Test
    fun ordinaryTypingIsLeftAlone() {
        assertNull(typed("**bold*"))
        assertNull(typed("snake_case_"))
        assertNull(typed("2 ** 3"))
        assertNull(typed("[bad](javascript:x)"))
        assertNull(typed("**bold**", BlockType.Code))
        assertNull(typed("a"))
    }
}
