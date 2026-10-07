package com.rendyhd.vicu.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthDebugLogRetentionTest {

    @Test
    fun `the file is not rewritten until it grows past the slack`() {
        assertFalse(AuthDebugLogRetention.shouldTrim(0))
        assertFalse(AuthDebugLogRetention.shouldTrim(AuthDebugLogRetention.MAX_LINES))
        assertFalse(AuthDebugLogRetention.shouldTrim(AuthDebugLogRetention.MAX_LINES + AuthDebugLogRetention.TRIM_SLACK))
        assertTrue(AuthDebugLogRetention.shouldTrim(AuthDebugLogRetention.MAX_LINES + AuthDebugLogRetention.TRIM_SLACK + 1))
    }

    @Test
    fun `trimming keeps only the newest lines`() {
        val lines = (1..AuthDebugLogRetention.MAX_LINES + 120).map { "line $it" }

        val trimmed = AuthDebugLogRetention.trim(lines)

        assertEquals(AuthDebugLogRetention.MAX_LINES, trimmed.size)
        assertEquals("line 121", trimmed.first())
        assertEquals("line ${lines.size}", trimmed.last())
    }

    @Test
    fun `short logs are returned untouched`() {
        val lines = listOf("a", "b", "c")
        assertEquals(lines, AuthDebugLogRetention.trim(lines))
    }
}
