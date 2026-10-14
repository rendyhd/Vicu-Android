package com.rendyhd.vicu.util

import kotlin.test.Test
import kotlin.test.assertEquals

class SqlLikeTest {

    @Test
    fun `plain text is wrapped in wildcards`() {
        assertEquals("%milk%", SqlLike.contains("milk"))
        assertEquals("%%", SqlLike.contains(""))
    }

    @Test
    fun `percent, underscore and the escape character are escaped`() {
        assertEquals("100\\%", SqlLike.escape("100%"))
        assertEquals("a\\_b", SqlLike.escape("a_b"))
        assertEquals("c:\\\\temp", SqlLike.escape("c:\\temp"))
        assertEquals("%50\\%\\_\\\\%", SqlLike.contains("50%_\\"))
    }

    @Test
    fun `other characters, quotes included, are left alone`() {
        assertEquals("it's [a-z]* (b)", SqlLike.escape("it's [a-z]* (b)"))
    }

    @Test
    fun `the escape character is a single backslash`() {
        assertEquals(1, SqlLike.ESCAPE_CHAR.toString().length)
        assertEquals('\\', SqlLike.ESCAPE_CHAR)
    }
}
