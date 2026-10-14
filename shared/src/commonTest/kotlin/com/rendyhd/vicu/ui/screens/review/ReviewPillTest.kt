package com.rendyhd.vicu.ui.screens.review

import com.rendyhd.vicu.util.ReviewMetadata
import com.rendyhd.vicu.util.ReviewState
import com.rendyhd.vicu.util.ReviewStatus
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewPillTest {

    private fun status(state: ReviewState, overdue: Boolean, since: Long?) = ReviewStatus(
        metadata = ReviewMetadata(state, null, null),
        effectiveCadenceDays = 14,
        nextReviewAt = LocalDate(2026, 10, 7),
        isOverdue = overdue,
        daysSinceReviewed = since,
        daysUntilDue = null,
    )

    @Test
    fun `a project never reviewed is grey and says so`() {
        assertEquals(StalenessPill("Not reviewed yet", amber = false), stalenessPill(status(ReviewState.NEVER, true, null)))
    }

    @Test
    fun `an overdue review is the one amber state`() {
        assertEquals(StalenessPill("Due", amber = true), stalenessPill(status(ReviewState.REVIEWED, true, 20)))
    }

    @Test
    fun `a review that is not due says how long ago`() {
        assertEquals(StalenessPill("3d ago", amber = false), stalenessPill(status(ReviewState.REVIEWED, false, 3)))
    }
}
