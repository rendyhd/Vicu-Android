package com.rendyhd.vicu.util

import kotlin.math.floor
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** A repeat rule (contract 20.4). Weekdays are 0 (Sunday) to 6 (Saturday). */
sealed interface EventRepeat {
    data object None : EventRepeat
    data class Daily(val every: Int) : EventRepeat
    /** On these days of the week, every [every] weeks (weeks start on Monday). */
    data class Weekly(val every: Int, val days: List<Int>) : EventRepeat
    /** On this date of the month; a month without it has none. */
    data class MonthlyDate(val every: Int, val date: Int) : EventRepeat
    /** On the [week]th [weekday] of the month (1 to 4), or the last one (-1). */
    data class MonthlyWeekday(val every: Int, val week: Int, val weekday: Int) : EventRepeat
    /** On the first day's month and date; 29 February only in leap years. */
    data class Yearly(val every: Int) : EventRepeat
}

/** One of your own Events (contract 20.3). */
data class PlanEvent(
    val title: String,
    /** The first day. */
    val start: LocalDate,
    /** "HH:mm", or null for an all-day Event. */
    val time: String?,
    /** Length in minutes, 1 to 1440 (timed); 0 for an all-day Event. */
    val minutes: Int,
    val repeat: EventRepeat = EventRepeat.None,
    /** The last day it may happen on. */
    val until: LocalDate? = null,
    /** Days left out ("skip this one"), sorted. */
    val skips: List<LocalDate> = emptyList(),
    /** False: a timed Event that does not count as busy. Null is the default (busy). */
    val busy: Boolean? = null,
    /** Fields this version does not know, kept and written back. */
    val kept: Map<String, JsonElement> = emptyMap(),
)

/** An Event with its synced id. */
data class ListedEvent(val id: String, val event: PlanEvent)

/** A repeat choice offered for a day (contract 20.5). */
data class RepeatChoice(val label: String, val repeat: EventRepeat)

/** A value an edit sets; an edit without one leaves the field alone. */
data class EditValue<out T>(val value: T)

/** What a change sets (contract 20.6). A null field stays as it was. */
data class EventEdit(
    val title: String? = null,
    /** A value of null makes it all-day. */
    val time: EditValue<String?>? = null,
    val minutes: Int? = null,
    val busy: Boolean? = null,
    val repeat: EventRepeat? = null,
    val until: EditValue<LocalDate?>? = null,
    /** Moves the occurrence being changed to this day. */
    val day: LocalDate? = null,
)

/** All times, this time only, this and following. */
enum class EventScope { ALL, THIS, FOLLOWING }

/**
 * Your own Events (docs/cross-app-semantics-v1.md section 20): times you are not available, typed
 * by hand, each one synced preference `event.<id>`. Times are wall-clock and the repeat rule is
 * Vicu's own. A line-for-line port of the desktop's `src/shared/plan-events.ts`, pinned by the
 * `planEvents` and `eventChanges` vectors. Pure: the zone is passed in.
 */
object PlanEvents {
    const val EVENT_KEY_PREFIX = "event."
    const val MY_EVENTS_CALENDAR_ID = CalendarEvents.MY_EVENTS_CALENDAR_ID
    const val MY_EVENTS_COLOR = "#12a594"

    /** The longest Event, in minutes. */
    const val MAX_EVENT_MINUTES = 24 * 60

    private val DAY = Regex("""^\d{4}-\d{2}-\d{2}$""")
    private val CLOCK = Regex("""^([01]\d|2[0-3]):[0-5]\d$""")
    private val KNOWN_FIELDS = setOf("title", "start", "time", "minutes", "repeat", "until", "skips", "busy")
    private val WEEKDAY_SET = listOf(1, 2, 3, 4, 5)

    fun eventKey(id: String): String = "$EVENT_KEY_PREFIX$id"

    // --- Reading and writing (20.3) ----------------------------------------------------------------

    /** A whole number in a JSON value, or null (a string or a fraction is not one). */
    private fun wholeNumber(value: JsonElement?): Int? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        val number = primitive.doubleOrNull ?: return null
        if (number != floor(number) || number > Int.MAX_VALUE || number < Int.MIN_VALUE) return null
        return number.toInt()
    }

    private fun text(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun dayOf(value: JsonElement?): LocalDate? =
        text(value)?.takeIf { DAY.matches(it) }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private fun isWeekday(value: Int?): Boolean = value != null && value in 0..6

    /** The repeat rule [value] holds, or null when it is not one this version reads. */
    fun readRepeat(value: JsonElement?): EventRepeat? {
        val r = value as? JsonObject ?: return null
        val every = if (r.containsKey("every")) wholeNumber(r["every"]) else 1
        val okEvery = every != null && every in 1..99
        return when (text(r["kind"])) {
            "none" -> EventRepeat.None
            "daily" -> if (okEvery) EventRepeat.Daily(every!!) else null
            "weekly" -> {
                val raw = r["days"] as? JsonArray ?: JsonArray(emptyList())
                val days = raw.map { wholeNumber(it) }
                if (!okEvery || days.isEmpty() || !days.all(::isWeekday)) null else EventRepeat.Weekly(every!!, sortedDays(days.map { it!! }))
            }
            "monthly-date" -> {
                val date = wholeNumber(r["date"])
                if (okEvery && date != null && date in 1..31) EventRepeat.MonthlyDate(every!!, date) else null
            }
            "monthly-weekday" -> {
                val week = wholeNumber(r["week"])
                val weekday = wholeNumber(r["weekday"])
                if (okEvery && week in listOf(1, 2, 3, 4, -1) && isWeekday(weekday)) {
                    EventRepeat.MonthlyWeekday(every!!, week!!, weekday!!)
                } else {
                    null
                }
            }
            "yearly" -> if (okEvery) EventRepeat.Yearly(every!!) else null
            else -> null
        }
    }

    private fun sortedDays(days: List<Int>): List<Int> = days.distinct().sorted()

    /** The Event [value] holds, or null when it is not one (it is then ignored and kept). */
    fun read(value: JsonElement?): PlanEvent? {
        val v = value as? JsonObject ?: return null
        val title = text(v["title"])?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val start = dayOf(v["start"]) ?: return null
        val rawTime = v["time"]
        val time = if (rawTime == null || rawTime is JsonNull) null else (text(rawTime)?.takeIf { CLOCK.matches(it) } ?: return null)
        var minutes = 0
        if (time != null) {
            minutes = wholeNumber(v["minutes"])?.takeIf { it in 1..MAX_EVENT_MINUTES } ?: return null
        }
        val rawRepeat = v["repeat"]
        val repeat = if (rawRepeat == null || rawRepeat is JsonNull) EventRepeat.None else (readRepeat(rawRepeat) ?: return null)
        val until = dayOf(v["until"])
        val skips = (v["skips"] as? JsonArray)?.mapNotNull { dayOf(it) }?.distinct()?.sorted().orEmpty()
        val busy = (v["busy"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        val kept = v.filterKeys { it !in KNOWN_FIELDS }
        return PlanEvent(title, start, time, minutes, repeat, until, skips, busy, kept)
    }

    fun encodeRepeat(repeat: EventRepeat): JsonObject = JsonObject(
        when (repeat) {
            EventRepeat.None -> mapOf("kind" to JsonPrimitive("none"))
            is EventRepeat.Daily -> mapOf("kind" to JsonPrimitive("daily"), "every" to JsonPrimitive(repeat.every))
            is EventRepeat.Yearly -> mapOf("kind" to JsonPrimitive("yearly"), "every" to JsonPrimitive(repeat.every))
            is EventRepeat.Weekly -> mapOf(
                "kind" to JsonPrimitive("weekly"),
                "every" to JsonPrimitive(repeat.every),
                "days" to JsonArray(sortedDays(repeat.days).map { JsonPrimitive(it) }),
            )
            is EventRepeat.MonthlyDate -> mapOf(
                "kind" to JsonPrimitive("monthly-date"),
                "every" to JsonPrimitive(repeat.every),
                "date" to JsonPrimitive(repeat.date),
            )
            is EventRepeat.MonthlyWeekday -> mapOf(
                "kind" to JsonPrimitive("monthly-weekday"),
                "every" to JsonPrimitive(repeat.every),
                "week" to JsonPrimitive(repeat.week),
                "weekday" to JsonPrimitive(repeat.weekday),
            )
        },
    )

    /**
     * What is written (20.3): unknown fields as they were, then the known ones without their
     * defaults. An all-day Event has no `time`, `minutes` or `busy`; `repeat` only when it repeats.
     */
    fun encode(event: PlanEvent): JsonObject {
        val written = LinkedHashMap<String, JsonElement>(event.kept)
        written["title"] = JsonPrimitive(event.title.trim())
        written["start"] = JsonPrimitive(event.start.toString())
        if (event.time != null) {
            written["time"] = JsonPrimitive(event.time)
            written["minutes"] = JsonPrimitive(event.minutes)
        }
        if (event.repeat != EventRepeat.None) written["repeat"] = encodeRepeat(event.repeat)
        event.until?.let { written["until"] = JsonPrimitive(it.toString()) }
        if (event.skips.isNotEmpty()) written["skips"] = JsonArray(event.skips.distinct().sorted().map { JsonPrimitive(it.toString()) })
        if (event.time != null && event.busy == false) written["busy"] = JsonPrimitive(false)
        return JsonObject(written)
    }

    /** The account's Events from the synced entries, by title, then id. */
    fun readAll(values: Map<String, JsonElement?>): List<ListedEvent> = values.mapNotNull { (key, value) ->
        if (!key.startsWith(EVENT_KEY_PREFIX) || key.length == EVENT_KEY_PREFIX.length) return@mapNotNull null
        read(value)?.let { ListedEvent(key.removePrefix(EVENT_KEY_PREFIX), it) }
    }.sortedWith(
        compareBy<ListedEvent, String>(String.CASE_INSENSITIVE_ORDER) { it.event.title }
            .thenBy { it.event.title }
            .thenBy { it.id },
    )

    /** Whether it counts as busy time (20.2): a timed Event unless its `busy` is false. */
    fun isBusy(event: PlanEvent): Boolean = event.time != null && event.busy != false

    // --- Days ----------------------------------------------------------------------------------------

    /** Days from [from] to [to] (negative when [to] is earlier). */
    fun daysBetween(from: LocalDate, to: LocalDate): Int = (to.toEpochDays() - from.toEpochDays()).toInt()

    /** 0 is Sunday ... 6 is Saturday. */
    fun weekdayOf(day: LocalDate): Int = day.dayOfWeek.isoDayNumber % 7

    private fun daysInMonth(day: LocalDate): Int =
        LocalDate(day.year, day.month.number, 1).plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY).day

    /** Monday-based week number, for "every 2 weeks". 1970-01-05 was a Monday: day 4. */
    private fun weekNumber(day: LocalDate): Long = (day.toEpochDays() - 4).floorDiv(7L)

    private fun monthsBetween(a: LocalDate, b: LocalDate): Int = (b.year - a.year) * 12 + (b.month.number - a.month.number)

    private fun weekOfMonth(day: LocalDate): Int = (day.day + 6) / 7

    private fun isLastWeek(day: LocalDate): Boolean = day.day + 7 > daysInMonth(day)

    /** Whether the rule gives [day], counted from [start] (no until, no skips). */
    private fun ruleGives(repeat: EventRepeat, start: LocalDate, day: LocalDate): Boolean {
        if (day < start) return false
        return when (repeat) {
            EventRepeat.None -> day == start
            is EventRepeat.Daily -> daysBetween(start, day) % repeat.every == 0
            is EventRepeat.Weekly ->
                weekdayOf(day) in repeat.days && (weekNumber(day) - weekNumber(start)) % repeat.every == 0L
            is EventRepeat.MonthlyDate -> day.day == repeat.date && monthsBetween(start, day) % repeat.every == 0
            is EventRepeat.MonthlyWeekday -> {
                if (weekdayOf(day) != repeat.weekday || monthsBetween(start, day) % repeat.every != 0) {
                    false
                } else if (repeat.week == -1) {
                    isLastWeek(day)
                } else {
                    weekOfMonth(day) == repeat.week
                }
            }
            is EventRepeat.Yearly ->
                day.month == start.month && day.day == start.day && (day.year - start.year) % repeat.every == 0
        }
    }

    /** Whether the Event happens on [day] (its start, its rule, its end and its skips). */
    fun occursOn(event: PlanEvent, day: LocalDate): Boolean {
        if (event.until != null && day > event.until) return false
        if (day in event.skips) return false
        return ruleGives(event.repeat, event.start, day)
    }

    /** The days it happens on from [from] to [to], both included. */
    fun daysBetween(event: PlanEvent, from: LocalDate, to: LocalDate): List<LocalDate> {
        val days = mutableListOf<LocalDate>()
        var day = maxOf(from, event.start)
        val last = event.until?.let { minOf(it, to) } ?: to
        while (day <= last) {
            if (occursOn(event, day)) days += day
            day = day.plus(1, DateTimeUnit.DAY)
        }
        return days
    }

    /** The day of the [count]th day the rule gives from [start] (skips not counted out), or null. */
    fun nthRuleDay(repeat: EventRepeat, start: LocalDate, count: Int): LocalDate? {
        if (count < 1) return null
        if (repeat == EventRepeat.None) return if (count == 1) start else null
        var seen = 0
        // At most 400 years of days: a rule that gives nothing (31 February) stops.
        for (i in 0 until 146_100) {
            val day = start.plus(i, DateTimeUnit.DAY)
            if (ruleGives(repeat, start, day)) {
                seen += 1
                if (seen == count) return day
            }
        }
        return null
    }

    /** A wall-clock time on [day], [minutes] from its midnight; past midnight it rolls on. */
    private fun wallClock(day: LocalDate, minutes: Int, zone: TimeZone) =
        LocalDateTime(day.plus(minutes / (24 * 60), DateTimeUnit.DAY), LocalTime((minutes % (24 * 60)) / 60, minutes % 60)).toInstant(zone)

    /** The Events' occurrences on [days] as calendar events of the Plan screen (20.1). */
    fun occurrences(events: List<ListedEvent>, days: List<LocalDate>, zone: TimeZone): List<CalendarEvent> {
        val out = mutableListOf<CalendarEvent>()
        for ((id, event) in events) {
            for (day in days) {
                if (!occursOn(event, day)) continue
                val time = event.time
                val start: kotlin.time.Instant
                val end: kotlin.time.Instant
                if (time == null) {
                    start = day.atStartOfDayIn(zone)
                    end = day.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone)
                } else {
                    // Wall clock: the start's clock time on that day, the end `minutes` later on the clock.
                    val clock = TimeBlocks.clockMinutes(time)
                    start = wallClock(day, clock, zone)
                    end = wallClock(day, clock + event.minutes, zone)
                }
                out += CalendarEvent(
                    id = "$MY_EVENTS_CALENDAR_ID:$id:$day",
                    calendarId = MY_EVENTS_CALENDAR_ID,
                    title = event.title,
                    start = start,
                    end = end,
                    allDay = time == null,
                    busy = isBusy(event),
                    tentative = false,
                    planEventId = id,
                    day = day,
                )
            }
        }
        return out
    }

    // --- Choices and words (20.5) ---------------------------------------------------------------------

    private val WEEKDAYS = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
    private val MONTHS = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )

    private fun ordinalWeek(week: Int): String = when (week) {
        1 -> "first"
        2 -> "second"
        3 -> "third"
        4 -> "fourth"
        else -> "last"
    }

    /** 1st, 2nd, 3rd, 4th ... 11th, 12th, 13th ... 21st. */
    fun ordinal(n: Int): String {
        val tens = n % 100
        if (tens in 11..13) return "${n}th"
        return n.toString() + when (n % 10) {
            1 -> "st"
            2 -> "nd"
            3 -> "rd"
            else -> "th"
        }
    }

    /** How a rule reads. */
    fun repeatText(repeat: EventRepeat): String = when (repeat) {
        EventRepeat.None -> "Does not repeat"
        is EventRepeat.Daily -> if (repeat.every == 1) "Every day" else "Every ${repeat.every} days"
        is EventRepeat.Weekly -> {
            if (repeat.every == 1 && repeat.days == WEEKDAY_SET) {
                "Every weekday"
            } else {
                // Named Monday first, the way the weeks count.
                val names = repeat.days.sortedBy { (it + 6) % 7 }.joinToString(", ") { WEEKDAYS[it] }
                if (repeat.every == 1) "Every $names" else "Every ${repeat.every} weeks on $names"
            }
        }
        is EventRepeat.MonthlyDate ->
            if (repeat.every == 1) "Monthly on the ${ordinal(repeat.date)}" else "Every ${repeat.every} months on the ${ordinal(repeat.date)}"
        is EventRepeat.MonthlyWeekday -> {
            val what = "the ${ordinalWeek(repeat.week)} ${WEEKDAYS[repeat.weekday]}"
            if (repeat.every == 1) "Monthly on $what" else "Every ${repeat.every} months on $what"
        }
        is EventRepeat.Yearly -> if (repeat.every == 1) "Every year" else "Every ${repeat.every} years"
    }

    /** The repeat choices offered for an Event starting on [day], in order. */
    fun repeatChoices(day: LocalDate): List<RepeatChoice> {
        val weekday = weekdayOf(day)
        val week = weekOfMonth(day)
        val name = WEEKDAYS[weekday]
        val choices = mutableListOf(
            RepeatChoice("Does not repeat", EventRepeat.None),
            RepeatChoice("Every day", EventRepeat.Daily(1)),
        )
        // Only on Monday to Friday: every choice happens on the day it is offered for.
        if (weekday in WEEKDAY_SET) choices += RepeatChoice("Every weekday", EventRepeat.Weekly(1, WEEKDAY_SET))
        choices += RepeatChoice("Every $name", EventRepeat.Weekly(1, listOf(weekday)))
        choices += RepeatChoice("Every 2 weeks on $name", EventRepeat.Weekly(2, listOf(weekday)))
        choices += RepeatChoice("Monthly on the ${ordinal(day.day)}", EventRepeat.MonthlyDate(1, day.day))
        if (week <= 4) {
            choices += RepeatChoice("Monthly on the ${ordinalWeek(week)} $name", EventRepeat.MonthlyWeekday(1, week, weekday))
        }
        if (isLastWeek(day)) {
            choices += RepeatChoice("Monthly on the last $name", EventRepeat.MonthlyWeekday(1, -1, weekday))
        }
        choices += RepeatChoice("Every year on ${MONTHS[day.month.number - 1]} ${day.day}", EventRepeat.Yearly(1))
        return choices
    }

    /** The index of [repeat] among [choices], or -1 when the list does not offer it. */
    fun choiceIndex(choices: List<RepeatChoice>, repeat: EventRepeat): Int {
        val key = encodeRepeat(repeat)
        return choices.indexOfFirst { encodeRepeat(it.repeat) == key }
    }

    /** The next day after [day] it happens on, within two years, or null. */
    fun nextOccurrence(event: PlanEvent, day: LocalDate): LocalDate? {
        for (i in 1..366 * 2) {
            val next = day.plus(i, DateTimeUnit.DAY)
            if (event.until != null && next > event.until) return null
            if (occursOn(event, next)) return next
        }
        return null
    }

    /** Whether it happens on any day before [day] (skips count as not happening). */
    fun hasOccurrenceBefore(event: PlanEvent, day: LocalDate): Boolean {
        var d = event.start
        while (d < day) {
            if (event.until != null && d > event.until) return false
            if (occursOn(event, d)) return true
            d = d.plus(1, DateTimeUnit.DAY)
        }
        return false
    }

    // --- Changes (20.6) -------------------------------------------------------------------------------

    /**
     * The rule moved with an occurrence from [from] to [to]: daily, yearly and none follow the first
     * day; weekly days move by the same number of days; monthly takes the date, or the week and
     * weekday, of [to] (the last week when [to] is in its month's last seven days and the rule said
     * last, or [to] is a fifth weekday).
     */
    fun repeatMovedTo(repeat: EventRepeat, from: LocalDate, to: LocalDate): EventRepeat {
        val delta = daysBetween(from, to)
        if (delta == 0) return repeat
        return when (repeat) {
            EventRepeat.None, is EventRepeat.Daily, is EventRepeat.Yearly -> repeat
            is EventRepeat.Weekly -> {
                val shift = ((delta % 7) + 7) % 7
                EventRepeat.Weekly(repeat.every, sortedDays(repeat.days.map { (it + shift) % 7 }))
            }
            is EventRepeat.MonthlyDate -> EventRepeat.MonthlyDate(repeat.every, to.day)
            is EventRepeat.MonthlyWeekday -> {
                val week = weekOfMonth(to)
                val next = if (week > 4 || (repeat.week == -1 && isLastWeek(to))) -1 else week
                EventRepeat.MonthlyWeekday(repeat.every, next, weekdayOf(to))
            }
        }
    }

    /** The Event's title, time, length and busy with the edit applied. */
    private fun withFields(event: PlanEvent, edit: EventEdit, defaultLength: Int): PlanEvent {
        val time = if (edit.time != null) edit.time.value else event.time
        val minutes = if (time == null) 0 else edit.minutes ?: (if (event.minutes > 0) event.minutes else defaultLength)
        return event.copy(
            title = (edit.title ?: event.title).trim(),
            time = time,
            minutes = minutes,
            busy = edit.busy ?: event.busy,
        )
    }

    /** Synced preference entries to write: `event.<id>` to an encoded Event, or null (deleted). */
    fun change(
        listed: ListedEvent,
        day: LocalDate,
        edit: EventEdit,
        scope: EventScope,
        newId: String,
        defaultLength: Int = TimeBlocks.DEFAULT_LENGTH_MINUTES,
    ): Map<String, JsonElement?> {
        val series = listed.event
        val to = edit.day ?: day
        val delta = daysBetween(day, to)
        fun shift(d: LocalDate) = d.plus(delta, DateTimeUnit.DAY)
        var effective = if (series.repeat == EventRepeat.None) EventScope.ALL else scope
        if (effective == EventScope.FOLLOWING && !hasOccurrenceBefore(series, day)) effective = EventScope.ALL

        val untilAfter = if (edit.until != null) edit.until.value else series.until?.let(::shift)
        return when (effective) {
            EventScope.ALL -> {
                val changed = withFields(series, edit, defaultLength).copy(
                    start = shift(series.start),
                    repeat = edit.repeat ?: repeatMovedTo(series.repeat, day, to),
                    until = untilAfter,
                    skips = series.skips.map(::shift),
                )
                mapOf(eventKey(listed.id) to encode(changed))
            }
            EventScope.THIS -> {
                val single = withFields(series, edit, defaultLength)
                    .copy(start = to, repeat = EventRepeat.None, until = null, skips = emptyList())
                mapOf(
                    eventKey(listed.id) to encode(series.copy(skips = series.skips + day)),
                    eventKey(newId) to encode(single),
                )
            }
            EventScope.FOLLOWING -> {
                val end = day.minus(1, DateTimeUnit.DAY)
                val before = series.copy(until = end, skips = series.skips.filter { it <= end })
                val after = withFields(series, edit, defaultLength).copy(
                    start = to,
                    repeat = edit.repeat ?: repeatMovedTo(series.repeat, day, to),
                    until = untilAfter,
                    skips = series.skips.filter { it > end }.map(::shift),
                )
                mapOf(eventKey(listed.id) to encode(before), eventKey(newId) to encode(after))
            }
        }
    }

    /** Skip this one: the day joins the skips. */
    fun skip(listed: ListedEvent, day: LocalDate): Map<String, JsonElement?> =
        mapOf(eventKey(listed.id) to encode(listed.event.copy(skips = listed.event.skips + day)))

    /** Delete: the entry becomes null on every device. */
    fun delete(id: String): Map<String, JsonElement?> = mapOf(eventKey(id) to null)

    /** Undo of [writes]: each entry as it was in [before] (the stored values), null when it was not there. */
    fun undo(writes: Map<String, JsonElement?>, before: Map<String, JsonElement?>): Map<String, JsonElement?> =
        writes.keys.associateWith { key -> before[key]?.takeUnless { it is JsonNull } }

    /** Whether a change of [edit] on a repeating Event may be "this time only" (20.6). */
    fun allowsThisOnly(edit: EventEdit): Boolean = edit.repeat == null && edit.until == null
}
