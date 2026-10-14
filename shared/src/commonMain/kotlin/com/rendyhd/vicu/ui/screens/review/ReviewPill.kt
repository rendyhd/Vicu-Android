package com.rendyhd.vicu.ui.screens.review

import com.rendyhd.vicu.util.ReviewState
import com.rendyhd.vicu.util.ReviewStatus

/** The staleness pill of a Review row: its text and whether it is the one amber state. */
data class StalenessPill(val text: String, val amber: Boolean)

/**
 * A project that was never reviewed is grey, not an alarm; an overdue review is the one amber state
 * ("Due"); anything else says how long ago. The same rule as the desktop `formatStalenessPill`.
 */
fun stalenessPill(status: ReviewStatus): StalenessPill = when {
    status.metadata.state == ReviewState.NEVER -> StalenessPill("Not reviewed yet", amber = false)
    status.isOverdue -> StalenessPill("Due", amber = true)
    else -> StalenessPill("${status.daysSinceReviewed ?: 0}d ago", amber = false)
}
