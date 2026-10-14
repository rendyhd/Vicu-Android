package com.rendyhd.vicu.ui.navigation

import com.rendyhd.vicu.domain.model.SharedContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedContentSaverTest {

    @Test
    fun `a text share survives being saved and restored`() {
        val shared = SharedContent(
            text = "Line one\nLine two with \"quotes\" and unicode é世界",
            subject = "A subject",
            mimeType = "text/plain",
        )
        assertEquals(shared, decodeSharedContent(encodeSharedContent(shared)))
    }

    @Test
    fun `file shares keep every uri and the mime type`() {
        val shared = SharedContent(
            text = null,
            subject = null,
            fileUris = listOf(
                "content://media/external/images/media/42",
                "content://com.example.provider/files/a%20b.pdf?x=1&y=2",
            ),
            mimeType = "image/*",
        )
        assertEquals(shared, decodeSharedContent(encodeSharedContent(shared)))
    }

    @Test
    fun `nothing pending is saved as an empty string`() {
        assertEquals("", encodeSharedContent(null))
        assertNull(decodeSharedContent(""))
    }

    @Test
    fun `a damaged saved value restores as nothing instead of failing`() {
        assertNull(decodeSharedContent("{not json"))
        assertNull(decodeSharedContent("[]"))
        assertNull(decodeSharedContent("{}"), "a share with nothing in it opens nothing")
    }

    @Test
    fun `a huge share is cut for the saved state so the state stays small`() {
        val huge = "x".repeat(MAX_SAVED_SHARE_CHARS * 3)
        val restored = decodeSharedContent(encodeSharedContent(SharedContent(text = huge)))
        val text = restored?.text.orEmpty()
        assertTrue(text.length <= MAX_SAVED_SHARE_CHARS + 1, "was ${text.length}")
        assertTrue(text.startsWith("xxx"))
    }
}
