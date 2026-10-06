package com.rendyhd.vicu.util

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.time.Duration

/** A [TimeSource] whose clock and zone a test moves by hand. */
class MutableTimeSource(
    var now: Instant,
    var zone: TimeZone = TimeZone.UTC,
) : TimeSource {
    override fun now(): Instant = now
    override fun zone(): TimeZone = zone

    fun advance(by: Duration) {
        now += by
    }
}
