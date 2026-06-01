package com.rendyhd.vicu.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncRemapTest {

    @Test
    fun `remaps add_label payload that references the temp id`() {
        assertEquals("42:7", remapLabelTaskPayload("-123:7", tempId = -123, realId = 42))
    }

    @Test
    fun `returns null when payload does not reference the temp id`() {
        assertNull(remapLabelTaskPayload("99:7", tempId = -123, realId = 42))
    }

    @Test
    fun `returns null for a malformed payload`() {
        assertNull(remapLabelTaskPayload("not-a-pair", tempId = -123, realId = 42))
    }
}
