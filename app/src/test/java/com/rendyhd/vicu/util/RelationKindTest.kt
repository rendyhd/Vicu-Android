package com.rendyhd.vicu.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelationKindTest {
    @Test
    fun `wire value for precedes has one e`() {
        assertEquals("precedes", RelationKind.PRECEDES)
    }

    @Test
    fun `labels are human readable`() {
        assertEquals("Blocked by", RelationKind.label(RelationKind.BLOCKED))
        assertEquals("Duplicate of", RelationKind.label(RelationKind.DUPLICATEOF))
        assertEquals("Parent task", RelationKind.label(RelationKind.PARENTTASK))
    }

    @Test
    fun `subtask and parenttask are not user-selectable`() {
        assertFalse(RelationKind.SELECTABLE.contains(RelationKind.SUBTASK))
        assertFalse(RelationKind.SELECTABLE.contains(RelationKind.PARENTTASK))
        assertTrue(RelationKind.SELECTABLE.contains(RelationKind.BLOCKING))
    }
}
