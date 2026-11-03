package com.rendyhd.vicu.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SharedContentTest {

    private val screenshot = "content://com.android.systemui/screenshot/42"

    @Test
    fun `a screenshot starts without a title so the user can type one`() {
        // What Android's screenshot Share sends: the image and a subject naming it.
        val shared = SharedContent(
            subject = "Screenshot (Nov 3, 2026, 10:12:34 AM)",
            fileUris = listOf(screenshot),
            mimeType = "image/png",
        )
        assertNull(shared.suggestedTitle)
        assertNull(shared.suggestedDescription)
    }

    @Test
    fun `a file shared under its file name starts without a title`() {
        val shared = SharedContent(
            text = "Screenshot_20261103-101234.png",
            subject = "Screenshot_20261103-101234.png",
            fileUris = listOf(screenshot),
            mimeType = "image/png",
        )
        assertNull(shared.suggestedTitle)
        assertNull(shared.suggestedDescription, "the text only repeats the subject")
    }

    @Test
    fun `text that comes with files goes into the notes`() {
        // A screenshot of a web page comes with the page's address.
        val shared = SharedContent(
            text = "https://example.com/article",
            subject = "Screenshot (Nov 3, 2026, 10:12:34 AM)",
            fileUris = listOf(screenshot),
            mimeType = "image/png",
        )
        assertNull(shared.suggestedTitle)
        assertEquals("https://example.com/article", shared.suggestedDescription)
    }

    @Test
    fun `a text share still takes its title from the subject or the first line`() {
        assertEquals("An article", SharedContent(text = "Read this\nlater", subject = "An article").suggestedTitle)
        assertEquals("Read this", SharedContent(text = "Read this\nlater").suggestedTitle)
        assertEquals("Read this\nlater", SharedContent(text = "Read this\nlater").suggestedDescription)
    }
}
