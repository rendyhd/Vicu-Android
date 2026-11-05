package com.rendyhd.vicu.ui

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.create
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the composer's "Paste image" sees and reads. The pasteboard itself is faked: a test binary is
 * not an app, so iOS gives it no connection to the real one. The staged files are real.
 */
class IosClipboardImageTest {
    private class FakeBoard(
        override var hasImages: Boolean = false,
        override var changeCount: Long = 1,
        val byType: Map<String, NSData> = emptyMap(),
        val drawn: NSData? = null,
    ) : ImageBoard {
        var asked = 0

        override fun data(type: String): NSData? {
            asked++
            return byType[type]
        }

        override fun drawnPng(): NSData? {
            asked++
            return drawn
        }
    }

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    private fun bytes(vararg values: Int): NSData {
        val array = ByteArray(values.size) { values[it].toByte() }
        return array.usePinned { NSData.create(bytes = it.addressOf(0), length = array.size.toULong()) }
    }

    private val png = bytes(0x89, 0x50, 0x4E, 0x47)
    private val jpeg = bytes(0xFF, 0xD8, 0xFF)
    private val drawn = bytes(0x89, 0x50, 0x4E, 0x47, 0x00)

    private fun assertStaged(path: String?, extension: String, content: NSData) {
        assertNotNull(path)
        assertTrue(path.substringAfterLast('/').matches(Regex("pasted-\\d+\\.$extension")), path)
        assertEquals(content.length, NSFileManager.defaultManager.contentsAtPath(path)?.length)
    }

    @Test
    fun withoutAnImageThereIsNoStamp() {
        assertNull(IosClipboardImage(FakeBoard(hasImages = false)).stamp())
    }

    @Test
    fun askingForTheStampDoesNotReadTheImage() {
        val board = FakeBoard(hasImages = true, byType = mapOf("public.png" to png))
        IosClipboardImage(board).stamp()
        assertEquals(0, board.asked, "reading is what makes iOS ask to allow the paste")
    }

    @Test
    fun theStampChangesWithThePasteboard() {
        val board = FakeBoard(hasImages = true)
        val clipboard = IosClipboardImage(board)
        val first = assertNotNull(clipboard.stamp())
        assertEquals(first, clipboard.stamp())
        board.changeCount = 2
        assertNotEquals(first, clipboard.stamp())
    }

    @Test
    fun aScreenshotIsStagedAsItCame() {
        val board = FakeBoard(hasImages = true, byType = mapOf("public.png" to png, "public.jpeg" to jpeg))
        assertStaged(IosClipboardImage(board).read(), "png", png)
    }

    @Test
    fun aJpegStaysAJpeg() {
        val board = FakeBoard(hasImages = true, byType = mapOf("public.jpeg" to jpeg), drawn = drawn)
        assertStaged(IosClipboardImage(board).read(), "jpg", jpeg)
    }

    @Test
    fun anythingElseIsDrawnToAPng() {
        val board = FakeBoard(hasImages = true, drawn = drawn)
        assertStaged(IosClipboardImage(board).read(), "png", drawn)
    }

    @Test
    fun aRefusedPasteReadsNothing() {
        assertNull(IosClipboardImage(FakeBoard(hasImages = true)).read())
    }
}
