package com.rendyhd.vicu.ui.components.task

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The notes of the editor are about as tall as the text they hold. */
class PlainNotesHeightTest {

    @Test
    fun `empty notes are one line tall`() {
        assertEquals(MIN_PLAIN_NOTES_DP, plainNotesHeightDp(""))
        assertEquals(MIN_PLAIN_NOTES_DP, plainNotesHeightDp("<p></p>"))
    }

    @Test
    fun `notes grow with their lines and wrap long ones`() {
        val short = plainNotesHeightDp("<p>One line</p>")
        val two = plainNotesHeightDp("<p>One line</p><p>Another</p>")
        val wrapped = plainNotesHeightDp("<p>" + "word ".repeat(30) + "</p>")
        assertTrue(two > short, "a second paragraph adds a line")
        assertTrue(wrapped > short, "a long paragraph wraps onto more lines")
    }

    @Test
    fun `lists count each item and the height stops at the maximum`() {
        val list = plainNotesHeightDp("<ul><li>a</li><li>b</li><li>c</li></ul>")
        assertEquals(3 * 28 + 28, list)
        assertEquals(MAX_PLAIN_NOTES_DP, plainNotesHeightDp("<p>x</p>".repeat(100)))
    }
}
