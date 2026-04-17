package com.rendyhd.vicu.util

import com.rendyhd.vicu.util.ImageTokens.ImageRef
import com.rendyhd.vicu.util.ImageTokens.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageTokensTest {

    @Test
    fun segmentDescription_splitsTextAndTokens() {
        val segments = ImageTokens.segmentDescription("hello [[image:42]] world [[image-pending:abc-123]] end")
        assertEquals(
            listOf(
                Segment.Text("hello "),
                Segment.Image(42L),
                Segment.Text(" world "),
                Segment.Pending("abc-123"),
                Segment.Text(" end"),
            ),
            segments,
        )
    }

    @Test
    fun segmentDescription_plainText_returnsSingleText() {
        assertEquals(listOf(Segment.Text("just notes")), ImageTokens.segmentDescription("just notes"))
    }

    @Test
    fun segmentDescription_empty_returnsEmpty() {
        assertEquals(emptyList<Segment>(), ImageTokens.segmentDescription(""))
    }

    @Test
    fun parseValue_stripsLeadingAndTrailingNewlines() {
        val (text, refs) = ImageTokens.parseValue("\n\nbody\n[[image:1]]\n[[image:2]]")
        assertEquals("body", text)
        assertEquals(
            listOf(ImageRef.Image(1L), ImageRef.Image(2L)),
            refs,
        )
    }

    @Test
    fun parseValue_emptyValue_returnsEmptyPair() {
        val (text, refs) = ImageTokens.parseValue("")
        assertEquals("", text)
        assertTrue(refs.isEmpty())
    }

    @Test
    fun buildValue_combinesTextAndTokens() {
        val result = ImageTokens.buildValue(
            "body",
            listOf(ImageRef.Image(7L), ImageRef.Pending("xyz")),
        )
        assertEquals("body\n[[image:7]]\n[[image-pending:xyz]]", result)
    }

    @Test
    fun buildValue_emptyText_returnsTokensOnly() {
        val result = ImageTokens.buildValue("", listOf(ImageRef.Image(7L)))
        assertEquals("[[image:7]]", result)
    }

    @Test
    fun buildValue_noImages_returnsTextOnly() {
        val result = ImageTokens.buildValue("body", emptyList())
        assertEquals("body", result)
    }

    @Test
    fun buildValue_emptyEverything_returnsEmpty() {
        assertEquals("", ImageTokens.buildValue("", emptyList()))
    }

    /**
     * Round-trip invariant: parseValue(buildValue(t, imgs)).first == t
     * This is the key invariant the DescriptionField composable relies on
     * to preserve cursor/selection through every keystroke.
     */
    @Test
    fun roundTripIdentity_textWithImages() {
        val cases = listOf(
            "hello world" to listOf(ImageRef.Image(1L)),
            "multi\nline\nbody" to listOf(ImageRef.Image(1L), ImageRef.Image(2L)),
            "" to listOf(ImageRef.Image(1L)),
            "just text" to emptyList(),
            "" to emptyList(),
            "with trailing space " to listOf(ImageRef.Image(42L)),
        )
        for ((text, imgs) in cases) {
            val built = ImageTokens.buildValue(text, imgs)
            val (parsedText, parsedImgs) = ImageTokens.parseValue(built)
            assertEquals("text identity failed for: '$text' with $imgs", text, parsedText)
            assertEquals("images identity failed for: '$text' with $imgs", imgs, parsedImgs)
        }
    }

    @Test
    fun appendImageToken_addsToEnd() {
        val result = ImageTokens.appendImageToken("my notes", 99L)
        assertEquals("my notes\n[[image:99]]", result)
    }

    @Test
    fun appendImageToken_onExistingImages_keepsOrder() {
        val input = ImageTokens.buildValue("body", listOf(ImageRef.Image(1L)))
        val result = ImageTokens.appendImageToken(input, 2L)
        assertEquals("body\n[[image:1]]\n[[image:2]]", result)
    }

    @Test
    fun appendImageToken_emptyDescription_returnsTokenOnly() {
        assertEquals("[[image:5]]", ImageTokens.appendImageToken("", 5L))
    }

    @Test
    fun replacePendingTokens_swapsKnownUuids() {
        val input = "body\n[[image-pending:abc]]\n[[image-pending:xyz]]"
        val result = ImageTokens.replacePendingTokens(input, mapOf("abc" to 1L, "xyz" to 2L))
        assertEquals("body\n[[image:1]]\n[[image:2]]", result)
    }

    @Test
    fun replacePendingTokens_unknownUuids_leftAlone() {
        val input = "[[image-pending:abc]]"
        assertEquals(input, ImageTokens.replacePendingTokens(input, mapOf("other" to 1L)))
    }

    @Test
    fun findImageRefs_returnsPositionalOrder() {
        val refs = ImageTokens.findImageRefs("a [[image:2]] b [[image:1]] c [[image-pending:x]]")
        assertEquals(
            listOf(ImageRef.Image(2L), ImageRef.Image(1L), ImageRef.Pending("x")),
            refs,
        )
    }
}
