package com.rendyhd.vicu.util

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Timestamps of routine data (docs/cross-app-semantics-v1.md, section 6.3).
 *
 * They are written as UTC ISO-8601 with exactly three fraction digits
 * (`2026-10-06T08:00:00.000Z`), the way desktop writes them; kotlinx `Instant.toString()` varies
 * the number of digits. They are compared as parsed instants, never as text: as text
 * `2026-10-01T08:00:00Z` sorts after `2026-10-01T08:00:00.100Z`, although it is the earlier one.
 */
object RoutineTime {
    /** [instant] as `YYYY-MM-DDTHH:mm:ss.SSSZ` in UTC. */
    fun format(instant: Instant): String {
        val t = instant.toLocalDateTime(TimeZone.UTC)
        val millis = instant.nanosecondsOfSecond / 1_000_000
        return buildString {
            append(t.year.toString().padStart(4, '0'))
            append('-').append(t.monthNumber.toString().padStart(2, '0'))
            append('-').append(t.dayOfMonth.toString().padStart(2, '0'))
            append('T').append(t.hour.toString().padStart(2, '0'))
            append(':').append(t.minute.toString().padStart(2, '0'))
            append(':').append(t.second.toString().padStart(2, '0'))
            append('.').append(millis.toString().padStart(3, '0'))
            append('Z')
        }
    }

    /** The current time of [time] in the format above. */
    fun now(time: TimeSource = SystemTimeSource): String = format(time.now())

    /** The instant of a stored timestamp, or null when it is empty or does not parse. */
    fun parse(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return try {
            Instant.parse(value)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Last-write-wins order: the later instant, then the larger device id. Positive when the left
     * write is newer, negative when the right one is, 0 when they are the same write. A timestamp
     * that does not parse sorts before every real instant.
     */
    fun compareWrites(
        leftTime: String,
        leftDevice: String,
        rightTime: String,
        rightDevice: String,
    ): Int {
        val left = parse(leftTime) ?: Instant.DISTANT_PAST
        val right = parse(rightTime) ?: Instant.DISTANT_PAST
        val byTime = left.compareTo(right)
        return if (byTime != 0) byTime else leftDevice.compareTo(rightDevice)
    }
}
