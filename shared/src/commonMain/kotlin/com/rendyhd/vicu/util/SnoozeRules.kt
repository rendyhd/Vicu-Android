package com.rendyhd.vicu.util

/** When a snoozed reminder fires again; pure, so the time comes from the caller's time source. */
object SnoozeRules {
    /** The "Snooze" notification action pushes the reminder back by this much. */
    const val SNOOZE_MILLIS = 15 * 60 * 1000L

    /** A snooze that was already due when the device came back (reboot) fires this far from now. */
    const val OVERDUE_GRACE_MILLIS = 60_000L

    fun triggerAfterSnooze(nowMillis: Long): Long = nowMillis + SNOOZE_MILLIS

    fun triggerAfterRestore(storedTriggerMillis: Long, nowMillis: Long): Long =
        maxOf(storedTriggerMillis, nowMillis + OVERDUE_GRACE_MILLIS)
}
