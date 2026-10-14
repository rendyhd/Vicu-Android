package com.rendyhd.vicu.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentFormattingTest {

    // ---- Content-Disposition filename ------------------------------------------------------

    @Test
    fun `a plain name is quoted as it is`() {
        assertEquals("filename=\"report.pdf\"", contentDispositionFileParameter("report.pdf"))
    }

    @Test
    fun `quotes and backslashes in the name are escaped so they cannot end the value`() {
        assertEquals(
            "filename=\"a\\\"b\\\\c.txt\"",
            contentDispositionFileParameter("a\"b\\c.txt"),
        )
    }

    @Test
    fun `line breaks and other control characters are dropped`() {
        val value = contentDispositionFileParameter("evil\r\nX-Injected: yes\u0000.txt")
        assertFalse(value.contains('\r'))
        assertFalse(value.contains('\n'))
        assertFalse(value.contains('\u0000'))
        assertEquals("filename=\"evilX-Injected: yes.txt\"", value)
    }

    @Test
    fun `a blank name falls back to a placeholder`() {
        assertEquals("filename=\"file\"", contentDispositionFileParameter("\r\n"))
    }

    @Test
    fun `a name outside ASCII also gets the percent-encoded form`() {
        val value = contentDispositionFileParameter("café レポート.pdf")

        assertTrue(value.startsWith("filename=\"caf_ ____.pdf\"; filename*=UTF-8''"), value)
        assertTrue(value.endsWith("caf%C3%A9%20%E3%83%AC%E3%83%9D%E3%83%BC%E3%83%88.pdf"), value)
    }

    // ---- server size limit -----------------------------------------------------------------

    @Test
    fun `sizes are read the way Vikunja reads them`() {
        assertEquals(20_000_000L, parseByteSize("20MB"))
        assertEquals(20_000_000L, parseByteSize("20 MB"))
        assertEquals(20_000_000L, parseByteSize("20mb"))
        assertEquals(1_500L, parseByteSize("1.5KB"))
        assertEquals(20L * 1024 * 1024, parseByteSize("20MiB"))
        assertEquals(1_000_000_000L, parseByteSize("1GB"))
        assertEquals(512L, parseByteSize("512"))
        assertEquals(512L, parseByteSize("512B"))
    }

    @Test
    fun `an unreadable or empty size is null so the default applies`() {
        assertNull(parseByteSize(null))
        assertNull(parseByteSize(""))
        assertNull(parseByteSize("lots"))
        assertNull(parseByteSize("20 parsecs"))
        assertNull(parseByteSize("-5MB"))
        assertNull(parseByteSize("0MB"))
    }

    @Test
    fun `sizes are shown in the units the limit is given in`() {
        assertEquals("512 B", formatByteSize(512))
        assertEquals("1.5 KB", formatByteSize(1_500))
        assertEquals("20 MB", formatByteSize(20_000_000))
        assertEquals("35.2 MB", formatByteSize(35_240_000))
        assertEquals("1 GB", formatByteSize(1_000_000_000))
    }

    // ---- cache file names ------------------------------------------------------------------

    @Test
    fun `a name cannot climb out of its folder`() {
        assertEquals("passwd", safeFileName("../../etc/passwd"))
        assertEquals("evil.txt", safeFileName("..\\..\\evil.txt"))
    }

    @Test
    fun `characters a file system refuses are replaced`() {
        assertEquals("a_b_c_d.txt", safeFileName("a:b*c?d.txt"))
    }

    @Test
    fun `an empty or dotted name gets a placeholder`() {
        assertEquals("attachment", safeFileName(""))
        assertEquals("attachment", safeFileName(".."))
        assertEquals("attachment", safeFileName("   "))
    }

    @Test
    fun `a very long name is shortened but keeps its extension`() {
        val name = safeFileName("x".repeat(300) + ".pdf")
        assertEquals(120, name.length)
        assertTrue(name.endsWith(".pdf"))
    }
}
