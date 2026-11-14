package com.rendyhd.vicu.util

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.isoDayNumber
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * The day a week starts on (docs/cross-app-semantics-v1.md, section 2.1): the synced preference
 * `week_start`. Monday is the default and is never written; any value the registry does not name
 * acts as Monday. The desktop has the same in `src/shared/week-start.ts`.
 *
 * It decides calendars, "this week" and which week "next week" is. It never changes a schedule:
 * "every 2 weeks" in a routine or an Event keeps counting from Monday.
 */
enum class WeekStart(val key: String, val day: DayOfWeek, val label: String) {
    MONDAY("monday", DayOfWeek.MONDAY, "Monday"),
    SUNDAY("sunday", DayOfWeek.SUNDAY, "Sunday"),
    SATURDAY("saturday", DayOfWeek.SATURDAY, "Saturday");

    /** How many days [of] is into a week that starts on this day: 0 for the first day, 6 for the last. */
    fun daysInto(of: DayOfWeek): Int = (of.isoDayNumber - day.isoDayNumber + 7) % 7

    /** The seven weekdays in the order of a week. */
    val weekdays: List<DayOfWeek>
        get() = List(7) { DayOfWeek.entries[(day.ordinal + it) % 7] }

    companion object {
        val DEFAULT = MONDAY

        /** The order the setting offers them in. */
        val CHOICES: List<WeekStart> = listOf(MONDAY, SUNDAY, SATURDAY)

        /** The week start a stored value gives; anything but the two written names is the default. */
        fun fromKey(key: String?): WeekStart = entries.firstOrNull { it.key == key } ?: DEFAULT

        /** The week start a synced entry gives ([fromKey] of its text; a number or an object is the default). */
        fun read(value: JsonElement?): WeekStart =
            fromKey((value as? JsonPrimitive)?.takeIf { it.isString }?.content)

        /** What the preference holds for [weekStart]: null for the default, so the reset reaches every device. */
        fun write(weekStart: WeekStart): JsonElement? =
            weekStart.takeUnless { it == DEFAULT }?.let { JsonPrimitive(it.key) }

        /** Vikunja's `settings.week_start` (0 Sunday to 6 Saturday) as a week start; null for a day Vicu does not offer. */
        fun ofVikunja(weekStart: Int?): WeekStart? = when (weekStart) {
            0 -> SUNDAY
            1 -> MONDAY
            6 -> SATURDAY
            else -> null
        }

        /**
         * The day the setting offers to take over from the Vikunja account: its week start while it
         * is one of the three and differs from the one [inUse]. Null: nothing to offer.
         */
        fun offer(vikunja: Int?, inUse: WeekStart): WeekStart? = ofVikunja(vikunja)?.takeUnless { it == inUse }
    }
}
