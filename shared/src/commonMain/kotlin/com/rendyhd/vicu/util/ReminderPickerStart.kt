package com.rendyhd.vicu.util

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * Where the reminder pickers open. [dateMillis] is what the Material date picker takes for a day
 * (the UTC midnight of it); [hour] and [minute] are local wall-clock time.
 */
data class ReminderPickerStart(val dateMillis: Long, val hour: Int, val minute: Int) {
    companion object {
        /**
         * Editing a reminder ([existing] is its stored instant) opens on its own local date and
         * time. A new one opens on the next whole hour from [now], on that hour's date, so a late
         * evening does not offer a time that has already passed.
         */
        fun of(existing: String?, now: Instant, zone: TimeZone): ReminderPickerStart {
            val instant = existing?.takeUnless { DateUtils.isNullDate(it) }?.let { DateUtils.parseIsoDate(it) }
            if (instant != null) {
                val local = instant.toLocalDateTime(zone)
                return ReminderPickerStart(dayMillis(local.date), local.hour, local.minute)
            }
            val soon = now.plus(1, DateTimeUnit.HOUR, zone).toLocalDateTime(zone)
            return ReminderPickerStart(dayMillis(soon.date), soon.hour, 0)
        }

        private fun dayMillis(date: LocalDate): Long =
            date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
    }
}
