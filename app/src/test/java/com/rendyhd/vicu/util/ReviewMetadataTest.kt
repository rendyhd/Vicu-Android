package com.rendyhd.vicu.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReviewMetadataTest {
    @Test
    fun `parse empty description is never`() {
        val m = ReviewMetadata.parse("")
        assertEquals(ReviewState.NEVER, m.state)
        assertNull(m.lastReviewedAt)
        assertNull(m.cadenceDaysOverride)
    }

    @Test
    fun `parse reviewed with weekly cadence converts to days`() {
        val desc = "Body\n\n---\n**Vicu review**: 2026-05-01 · every 2 weeks"
        val m = ReviewMetadata.parse(desc)
        assertEquals(ReviewState.REVIEWED, m.state)
        assertEquals("2026-05-01", m.lastReviewedAt)
        assertEquals(14, m.cadenceDaysOverride)
    }

    @Test
    fun `parse excluded`() {
        val m = ReviewMetadata.parse("x\n\n---\n**Vicu review**: excluded")
        assertEquals(ReviewState.EXCLUDED, m.state)
    }

    @Test
    fun `serialize reviewed with cadence`() {
        val m = ReviewMetadata(ReviewState.REVIEWED, "2026-05-01", 14)
        assertEquals("**Vicu review**: 2026-05-01 · every 14 days", ReviewMetadata.serialize(m))
    }

    @Test
    fun `serialize excluded omits cadence`() {
        val m = ReviewMetadata(ReviewState.EXCLUDED, null, 30)
        assertEquals("**Vicu review**: excluded", ReviewMetadata.serialize(m))
    }

    @Test
    fun `upsert into empty description has no leading newlines`() {
        val out = ReviewMetadata.upsert("", ReviewMetadata(ReviewState.NEVER, null, null))
        assertEquals("---\n**Vicu review**: never", out)
    }

    @Test
    fun `upsert preserves body and replaces existing footer`() {
        val first = ReviewMetadata.upsert("Body", ReviewMetadata(ReviewState.NEVER, null, null))
        val second = ReviewMetadata.upsert(first, ReviewMetadata(ReviewState.REVIEWED, "2026-05-01", null))
        assertTrue(second.startsWith("Body\n\n---\n"))
        assertEquals(ReviewState.REVIEWED, ReviewMetadata.parse(second).state)
        // body must not accumulate footers
        assertEquals(1, Regex("Vicu review").findAll(second).count())
    }

    @Test
    fun `status never is overdue`() {
        val s = ReviewMetadata.computeStatus(
            ReviewMetadata(ReviewState.NEVER, null, null), 14, LocalDate.of(2026, 5, 31),
        )
        assertTrue(s.isOverdue)
        assertNull(s.daysUntilDue)
    }

    @Test
    fun `status reviewed within cadence is not overdue`() {
        val s = ReviewMetadata.computeStatus(
            ReviewMetadata(ReviewState.REVIEWED, "2026-05-30", null), 14, LocalDate.of(2026, 5, 31),
        )
        assertFalse(s.isOverdue)
        assertEquals(13L, s.daysUntilDue)
        assertEquals(14, s.effectiveCadenceDays)
    }

    @Test
    fun `status reviewed past cadence is overdue`() {
        val s = ReviewMetadata.computeStatus(
            ReviewMetadata(ReviewState.REVIEWED, "2026-05-01", 14), 30, LocalDate.of(2026, 5, 31),
        )
        assertTrue(s.isOverdue) // override 14 used, not global 30
        // reviewed 2026-05-01, cadence 14 -> due 2026-05-15; on 2026-05-31 that is 16 days overdue
        assertEquals(-16L, s.daysUntilDue)
    }

    @Test
    fun `status excluded is not overdue`() {
        val s = ReviewMetadata.computeStatus(
            ReviewMetadata(ReviewState.EXCLUDED, null, null), 14, LocalDate.of(2026, 5, 31),
        )
        assertFalse(s.isOverdue)
    }
}
