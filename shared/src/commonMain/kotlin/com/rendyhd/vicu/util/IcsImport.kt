package com.rendyhd.vicu.util

import com.rendyhd.vicu.util.ics.IcsCalendar
import com.rendyhd.vicu.util.ics.IcsEvent
import com.rendyhd.vicu.util.ics.IcsInstance
import com.rendyhd.vicu.util.ics.IcsRecur
import com.rendyhd.vicu.util.ics.IcsRecurrence
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonElement

/** One event or occurrence, in the importing device's local time. */
data class IcsOccurrence(
    /** SUMMARY as written, "" when there is none. */
    val title: String,
    /** The local day it starts on. */
    val start: LocalDate,
    /** The local start, "HH:mm", or null for an all-day event. */
    val time: String?,
    /** A timed event's length in minutes (end less start). */
    val minutes: Double,
    /** The days an all-day event covers, at least 1. */
    val days: Int,
    /** False for TRANSP:TRANSPARENT. */
    val busy: Boolean,
    /** STATUS:CANCELLED. */
    val cancelled: Boolean,
)

/** A VEVENT with its recurrence, as the file has it. */
data class IcsSeries(
    val occurrence: IcsOccurrence,
    val rule: IcsRecur?,
    /** UNTIL in the importing device's local time: `YYYY-MM-DD` for a date, else `YYYY-MM-DDTHH:mm:ss`. */
    val until: String?,
    /** EXDATE days, local. */
    val exdates: List<LocalDate>,
    /** RDATE, more than one RRULE, or overrides (RECURRENCE-ID): it never becomes one Event. */
    val irregular: Boolean,
)

/** What an import found: the Events in order, ready to write. */
data class IcsImportPreview(val events: List<PlanEvent>) {
    val repeating: Int get() = events.count { it.repeat != EventRepeat.None }
    val single: Int get() = events.size - repeating
}

/**
 * Importing a calendar file into Events, once (docs/cross-app-semantics-v1.md section 20.10). A
 * series whose RRULE fits Vicu's repeat rule (20.4) becomes one repeating Event; anything else is
 * expanded by the reader (`util/ics`: RRULE, RDATE, EXDATE, overrides, the file's own time zones)
 * and becomes single Events for the import day and the 365 days after it. A line-for-line port of
 * the desktop's `src/shared/ics-import.ts` and `src/main/calendar/ics-import.ts`, pinned by the
 * `icsImport` vectors. Pure: the zone and the import day are passed in.
 */
object IcsImport {
    /** Days after the import day that a series which does not fit the repeat rule is copied for. */
    const val IMPORT_WINDOW_DAYS = 365

    /** The title of an event without a SUMMARY. */
    const val UNTITLED_EVENT = "(No title)"

    private const val MAX_OCCURRENCES = 20_000

    private fun windowEnd(importDay: LocalDate): LocalDate = importDay.plus(IMPORT_WINDOW_DAYS, DateTimeUnit.DAY)

    // --- The pure rules (shared/ics-import.ts) ----------------------------------------------------------

    /** The repeat rule an RRULE is, for a series starting on [start], or null when it does not fit. */
    fun repeatFromRule(rule: IcsRecur, start: LocalDate): EventRepeat? {
        if (rule.otherParts.isNotEmpty()) return null
        val every = rule.interval
        if (every !in 1..99) return null
        val days = rule.byDayText.map(IcsRecurrence::readByDay)
        if (days.any { it == null }) return null
        val byDay = days.map { it!! }
        val plain = byDay.all { it.n == null }
        fun weekdays() = byDay.map { it.weekday }.distinct().sorted()
        val weekday = PlanEvents.weekdayOf(start)

        return when (rule.freq) {
            "DAILY" -> when {
                rule.byMonthDay.isNotEmpty() || rule.byMonth.isNotEmpty() || rule.bySetPos.isNotEmpty() -> null
                byDay.isEmpty() -> EventRepeat.Daily(every)
                // Some apps write "every weekday" this way.
                plain && every == 1 -> EventRepeat.Weekly(1, weekdays())
                else -> null
            }
            "WEEKLY" -> when {
                rule.byMonthDay.isNotEmpty() || rule.byMonth.isNotEmpty() || rule.bySetPos.isNotEmpty() || !plain -> null
                every > 1 && rule.wkst != null && rule.wkst != "MO" -> null
                else -> EventRepeat.Weekly(every, if (byDay.isNotEmpty()) weekdays() else listOf(weekday))
            }
            "MONTHLY" -> {
                if (rule.byMonth.isNotEmpty()) return null
                if (byDay.isEmpty()) {
                    if (rule.bySetPos.isNotEmpty()) return null
                    if (rule.byMonthDay.isEmpty()) return EventRepeat.MonthlyDate(every, start.day)
                    val date = rule.byMonthDay[0]
                    return if (rule.byMonthDay.size == 1 && date in 1..31) EventRepeat.MonthlyDate(every, date) else null
                }
                if (rule.byMonthDay.isNotEmpty() || byDay.size != 1) return null
                val only = byDay[0]
                val week = if (only.n != null) {
                    if (rule.bySetPos.isNotEmpty()) return null
                    only.n
                } else {
                    if (rule.bySetPos.size != 1) return null
                    rule.bySetPos[0]
                }
                if (week !in listOf(1, 2, 3, 4, -1)) null else EventRepeat.MonthlyWeekday(every, week, only.weekday)
            }
            "YEARLY" -> when {
                byDay.isNotEmpty() || rule.bySetPos.isNotEmpty() -> null
                rule.byMonth.size > 1 || (rule.byMonth.size == 1 && rule.byMonth[0] != start.month.number) -> null
                rule.byMonthDay.size > 1 || (rule.byMonthDay.size == 1 && rule.byMonthDay[0] != start.day) -> null
                else -> EventRepeat.Yearly(every)
            }
            else -> null
        }
    }

    /** The last day a series may happen on: the COUNTth day of its rule, or the local day of UNTIL. */
    fun untilFromRule(rule: IcsRecur, until: String?, repeat: EventRepeat, start: LocalDate, time: String?): LocalDate? {
        rule.count?.let { return PlanEvents.nthRuleDay(repeat, start, it) ?: start }
        if (until == null) return null
        val day = LocalDate.parse(until.substringBefore('T'))
        val clock = until.substringAfter('T', "")
        // An occurrence starting after UNTIL on its last day is not in the series.
        return if (clock.isNotEmpty() && time != null && "$time:00" > clock) day.minus(1, DateTimeUnit.DAY) else day
    }

    private fun titleOf(event: IcsOccurrence, busyOnly: Boolean): String {
        if (busyOnly) return if (event.busy) "Busy" else "Free"
        return event.title.trim().ifEmpty { UNTITLED_EVENT }
    }

    private fun lengthOf(minutes: Double): Int =
        minOf(PlanEvents.MAX_EVENT_MINUTES, maxOf(1, jsRound(minutes)))

    /** Math.round of JavaScript: halves go up. */
    private fun jsRound(value: Double): Int = kotlin.math.floor(value + 0.5).toInt()

    /**
     * One event or occurrence as a single Event, or null when it is cancelled, ends before the
     * import day begins or starts after the window. An all-day event of several days repeats daily
     * until its last day.
     */
    fun importOccurrence(event: IcsOccurrence, importDay: LocalDate, busyOnly: Boolean): PlanEvent? {
        if (event.cancelled) return null
        if (event.start > windowEnd(importDay)) return null
        val offset = PlanEvents.daysBetween(importDay, event.start)
        val title = titleOf(event, busyOnly)
        if (event.time == null) {
            val days = maxOf(1, event.days)
            if (offset + days <= 0) return null
            return if (days == 1) {
                PlanEvent(title, event.start, null, 0)
            } else {
                PlanEvent(title, event.start, null, 0, EventRepeat.Daily(1), until = event.start.plus(days - 1, DateTimeUnit.DAY))
            }
        }
        val minutes = lengthOf(event.minutes)
        if (offset * 1440 + TimeBlocks.clockMinutes(event.time) + minutes <= 0) return null
        return PlanEvent(title, event.start, event.time, minutes, busy = if (event.busy) null else false)
    }

    /**
     * A series as one repeating Event (empty: nothing to import, it is cancelled or ended before the
     * import day), or null when it does not fit the repeat rule and must be expanded into
     * occurrences for [importOccurrence]. An event without a rule is one occurrence.
     */
    fun importSeries(series: IcsSeries, importDay: LocalDate, busyOnly: Boolean): List<PlanEvent>? {
        val first = series.occurrence
        if (first.cancelled) return emptyList()
        if (series.irregular) return null
        val rule = series.rule ?: return listOfNotNull(importOccurrence(first, importDay, busyOnly))
        if (first.time == null && first.days > 1) return null
        val repeat = repeatFromRule(rule, first.start) ?: return null
        // The first day must be one the rule gives, or the series has an occurrence Vicu's rule cannot hold.
        if (!PlanEvents.occursOn(PlanEvent("", first.start, first.time, 0, repeat), first.start)) return null
        val until = untilFromRule(rule, series.until, repeat, first.start, first.time)
        if (until != null && until < importDay) return emptyList()
        val ruled = PlanEvent(titleOf(first, busyOnly), first.start, first.time, 0, repeat, until)
        val skips = series.exdates.filter { PlanEvents.occursOn(ruled, it) }.distinct().sorted()
        return listOf(
            ruled.copy(
                minutes = if (first.time == null) 0 else lengthOf(first.minutes),
                skips = skips,
                busy = if (first.time != null && !first.busy) false else null,
            ),
        )
    }

    /** Imported Events in a stable order: by first day, all-day first, then time, then title. */
    fun sortImported(events: List<PlanEvent>): List<PlanEvent> = events.sortedWith(
        compareBy<PlanEvent> { it.start }
            .thenBy { it.time ?: "" }
            .thenBy { it.title }
            .thenBy { PlanEvents.encode(it).toString() },
    )

    // --- The reader's half (main/calendar/ics-import.ts) ---------------------------------------------

    private fun pad(n: Int) = n.toString().padStart(2, '0')

    private fun occurrenceOf(instance: IcsInstance, zone: TimeZone): IcsOccurrence {
        val component = instance.component
        val title = component.text("SUMMARY").orEmpty()
        val busy = component.first("TRANSP")?.value?.trim()?.uppercase() != "TRANSPARENT"
        val cancelled = component.first("STATUS")?.value?.trim()?.uppercase() == "CANCELLED"
        if (instance.allDay) {
            return IcsOccurrence(title, instance.startDate, null, 0.0, maxOf(1, instance.days), busy, cancelled)
        }
        val from = instance.start!!
        val to = instance.end ?: from
        val local = from.toLocalDateTime(zone)
        return IcsOccurrence(
            title = title,
            start = local.date,
            time = "${pad(local.hour)}:${pad(local.minute)}",
            minutes = (to - from).inWholeMilliseconds / 60_000.0,
            days = 1,
            busy = busy,
            cancelled = cancelled,
        )
    }

    private fun seriesOf(event: IcsEvent, zone: TimeZone): IcsSeries? {
        val base = event.base() ?: return null
        if (event.start == null) return null
        val rule = event.rules.singleOrNull()
        val until = rule?.until?.let { u ->
            if (u.isDate) {
                u.date.toString()
            } else {
                // UNTIL without Z is in the series' zone (the reader parsed it with DTSTART's TZID).
                val local = event.instantOf(u).toLocalDateTime(zone)
                "${local.date}T${pad(local.hour)}:${pad(local.minute)}:${pad(local.second)}"
            }
        }
        val exdates = event.exdates.map { ex -> if (ex.isDate) ex.date else event.instantOf(ex).toLocalDateTime(zone).date }
        return IcsSeries(
            occurrence = occurrenceOf(base, zone),
            rule = rule,
            until = until,
            exdates = exdates,
            irregular = event.component.all("RRULE").size > 1 || event.hasRdates || event.overrides.isNotEmpty(),
        )
    }

    /** The occurrences of a series that may fall in the import window, each as a single Event. */
    private fun expanded(event: IcsEvent, importDay: LocalDate, zone: TimeZone, busyOnly: Boolean): List<PlanEvent> {
        // A moved occurrence can land a while after its own date: read a month past the window.
        val stop = windowEnd(importDay).plus(32, DateTimeUnit.DAY).atStartOfDayIn(zone)
        return event.instances(stop, MAX_OCCURRENCES).mapNotNull { importOccurrence(occurrenceOf(it, zone), importDay, busyOnly) }
    }

    /**
     * The Events a calendar file becomes when imported on [importDay] in [zone], in a stable order.
     * A file without events gives none.
     */
    fun importText(text: String, importDay: LocalDate, zone: TimeZone, busyOnly: Boolean): List<PlanEvent> {
        val calendar = IcsCalendar.read(text, zone)
        val out = mutableListOf<PlanEvent>()
        for (event in calendar.events) {
            if (event.isOrphanOverride) {
                // An override whose series is not in the file: one occurrence.
                event.base()?.let { importOccurrence(occurrenceOf(it, zone), importDay, busyOnly) }?.let { out += it }
                continue
            }
            val series = seriesOf(event, zone) ?: continue
            out += importSeries(series, importDay, busyOnly) ?: expanded(event, importDay, zone, busyOnly)
        }
        return sortImported(out)
    }

    /** Whether [text] looks like a calendar file at all. */
    fun isCalendar(text: String): Boolean = text.contains("BEGIN:VCALENDAR", ignoreCase = true) || text.contains("BEGIN:VEVENT", ignoreCase = true)

    /** The synced entries an import writes: each Event under a new id, in one change (20.10). */
    fun writes(events: List<PlanEvent>, newId: () -> String): Map<String, JsonElement?> =
        events.associate { PlanEvents.eventKey(newId()) to PlanEvents.encode(it) }

    /** "3 repeating events and 12 single events", for the preview. */
    fun summary(preview: IcsImportPreview): String {
        fun count(n: Int, what: String) = if (n == 1) "1 $what event" else "$n $what events"
        return when {
            preview.events.isEmpty() -> "No events to import"
            preview.repeating == 0 -> count(preview.single, "single")
            preview.single == 0 -> count(preview.repeating, "repeating")
            else -> "${count(preview.repeating, "repeating")} and ${count(preview.single, "single")}"
        }
    }
}
