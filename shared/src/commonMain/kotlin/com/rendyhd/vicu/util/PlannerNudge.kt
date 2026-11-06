package com.rendyhd.vicu.util

import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/**
 * Plan my day's evening nudge (contract 14.10): a per-device time for one notification each evening
 * while the planner is on. Each platform shows it its own way; a tap opens Plan.
 */
object PlannerNudge {
    const val DEFAULT_TIME = "21:30"
    const val TITLE = "Plan tomorrow"
    const val BODY = "Set up tomorrow in Plan my day."

    private val clock = Regex("""^([01]\d|2[0-3]):[0-5]\d$""")

    /** The time a notification is due at, or null while there is none: the planner is off or no time is set. */
    fun timeOf(plannerEnabled: Boolean, nudge: String?): String? =
        nudge?.takeIf { plannerEnabled && clock.matches(it) }

    /** The next time [clockTime] comes round after [after], so it lands on the time across a clock change. */
    fun nextAfter(after: Instant, zone: TimeZone, clockTime: String): Instant {
        val minutes = TimeBlocks.clockMinutes(clockTime)
        return DailySummary.nextOccurrence(after, zone, minutes / 60, minutes % 60)
    }
}
