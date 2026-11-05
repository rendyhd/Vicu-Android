package com.rendyhd.vicu.util

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * What a time block is (docs/cross-app-semantics-v1.md section 16.1), the same as the desktop's
 * `src/shared/time-blocks.ts`. A block is the task's own start_date and end_date; anything else in
 * those fields is not a block and is left alone.
 */
object TimeBlocks {
    /** A block is at most 12 hours. */
    const val MAX_BLOCK_MINUTES = 720

    /**
     * Both set, the end after the start, at most 12 hours apart, and the end no later than the
     * midnight that ends the start's day in [zone].
     */
    fun isBlock(start: Instant?, end: Instant?, zone: TimeZone): Boolean {
        if (start == null || end == null) return false
        val length = end - start
        if (length <= Duration.ZERO || length > MAX_BLOCK_MINUTES.minutes) return false
        val nextMidnight = start.toLocalDateTime(zone).date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone)
        return end <= nextMidnight
    }

    /** The same for stored values; a blank or null date is no date. */
    fun isBlock(start: String?, end: String?, zone: TimeZone): Boolean =
        isBlock(instantOf(start), instantOf(end), zone)

    private fun instantOf(value: String?): Instant? {
        if (value.isNullOrBlank() || value == Constants.NULL_DATE_STRING) return null
        return runCatching { Instant.parse(value) }.getOrNull()
    }
}
