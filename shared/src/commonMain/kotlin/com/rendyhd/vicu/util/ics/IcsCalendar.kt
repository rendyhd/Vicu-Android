package com.rendyhd.vicu.util.ics

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

/**
 * One instance of an event: an all-day one by its dates, a timed one by its instants. [component] is
 * where its title and state come from: the series, or the override (RECURRENCE-ID) that replaced it.
 */
class IcsInstance(
    val component: IcsComponent,
    val allDay: Boolean,
    /** All-day: the first day and how many days it covers (at least 1). */
    val startDate: LocalDate,
    val days: Int,
    /** Timed: the start and the end. */
    val start: Instant?,
    val end: Instant?,
)

/**
 * A VEVENT as the file has it: a series (with the overrides of its UID) or a single event, or an
 * override whose series is not in the file ([isOrphanOverride]).
 */
class IcsEvent internal constructor(
    val component: IcsComponent,
    val overrides: List<IcsComponent>,
    private val zones: IcsZones.Resolver,
) {
    val isOrphanOverride: Boolean get() = component.first("RECURRENCE-ID") != null
    val start: IcsTime? = IcsText.timeOf(component.first("DTSTART"))
    val rules: List<IcsRecur> = component.all("RRULE").mapNotNull { IcsRecur.parse(it.value, start?.tzid) }
    val hasRdates: Boolean get() = component.all("RDATE").isNotEmpty()

    /** The zone the series' times are expanded in (floating: the device's). */
    val zone: IcsZone? get() = start?.let(zones::zoneOf)

    /** The instant of a DATE-TIME value of this file. */
    fun instantOf(time: IcsTime): Instant = zones.instantOf(time)

    /** The EXDATE values. */
    val exdates: List<IcsTime> get() = component.all("EXDATE").flatMap(IcsText::times)

    /** The event itself, at its DTSTART: the first instance of a series. */
    fun base(): IcsInstance? = instanceOf(component, null)

    private fun lengthOf(event: IcsComponent, start: IcsTime): Duration? {
        IcsText.timeOf(event.first("DTEND"))?.let { end ->
            if (start.isDate) {
                return ((end.date.toEpochDays() - start.date.toEpochDays()).toLong() * 86_400L).seconds
            }
            return if (end.isDate) {
                zones.zoneOf(start).toInstant(LocalDateTime(end.date, LocalTime(0, 0))) - zones.instantOf(start)
            } else {
                zones.instantOf(end) - zones.instantOf(start)
            }
        }
        event.first("DURATION")?.let { IcsText.durationSeconds(it.value) }?.let { return it.seconds }
        return null
    }

    /**
     * The instance [event] (the series or an override) makes; [at] replaces its DTSTART for a
     * series' later instance (wall-clock in the series' zone), keeping the series' length.
     */
    private fun instanceOf(event: IcsComponent, at: LocalDateTime?): IcsInstance? {
        val own = IcsText.timeOf(event.first("DTSTART"))
            ?: IcsText.timeOf(event.first("RECURRENCE-ID"))
            ?: return null
        val first = if (at != null) own.copy(date = at.date, time = if (own.isDate) null else at.time) else own
        val length = lengthOf(event, own) ?: (if (event !== component) start?.let { lengthOf(component, it) } else null)
        if (first.isDate) {
            val days = ((length?.inWholeSeconds ?: 86_400L) / 86_400L).toInt().coerceAtLeast(1)
            return IcsInstance(event, allDay = true, startDate = first.date, days = days, start = null, end = null)
        }
        val from = zones.instantOf(first)
        return IcsInstance(event, allDay = false, startDate = first.date, days = 1, start = from, end = from + (length ?: Duration.ZERO))
    }

    /**
     * Every instance of the series that starts before [stop]: the RRULEs and RDATEs with DTSTART,
     * EXDATE left out, overrides in place of the instances they replace. A rule's runaway is capped.
     */
    fun instances(stop: Instant, limit: Int = 20_000): List<IcsInstance> {
        val first = start ?: return emptyList()
        val zone = zones.zoneOf(first)
        val stopWall = zone.toWall(stop)
        val isDate = first.isDate
        // Every value is compared as wall-clock time in the series' zone.
        fun wallOf(time: IcsTime): LocalDateTime =
            if (isDate || time.isDate) LocalDateTime(time.date, LocalTime(0, 0)) else zone.toWall(zones.instantOf(time))

        val starts = orderedSet<LocalDateTime>()
        starts += first.wall
        for (rule in rules) {
            val until = rule.until?.let { u -> if (u.isDate) LocalDateTime(u.date, LocalTime(23, 59, 59)) else wallOf(u) }
            starts += IcsRecurrence.expand(rule, first.wall, isDate, until, stopWall, limit)
        }
        component.all("RDATE").flatMap(IcsText::times).forEach { starts += wallOf(it) }

        val excludedWalls = HashSet<LocalDateTime>()
        val excludedDates = HashSet<LocalDate>()
        for (ex in exdates) {
            if (ex.isDate) excludedDates += ex.date else excludedWalls += wallOf(ex)
        }
        val replaced = HashMap<LocalDateTime, IcsComponent>()
        for (override in overrides) {
            val id = IcsText.timeOf(override.first("RECURRENCE-ID")) ?: continue
            replaced[wallOf(id)] = override
        }

        val out = mutableListOf<IcsInstance>()
        for (wall in starts) {
            if (wall >= stopWall || out.size >= limit) break
            if (wall in excludedWalls || wall.date in excludedDates) continue
            val instance = replaced[wall]?.let { instanceOf(it, null) } ?: instanceOf(component, wall)
            if (instance != null) out += instance
        }
        return out
    }

    private fun <T : Comparable<T>> orderedSet(): MutableSet<T> = SortedList()

    /** A set kept in order (common Kotlin has no TreeSet). */
    private class SortedList<T : Comparable<T>> : AbstractMutableSet<T>() {
        private val items = mutableListOf<T>()
        override val size: Int get() = items.size
        override fun add(element: T): Boolean {
            val index = items.binarySearch(element)
            if (index >= 0) return false
            items.add(-index - 1, element)
            return true
        }
        override fun iterator(): MutableIterator<T> = items.iterator()
    }
}

/** A calendar file: its events, each override with its series (contract 20.9 and 20.10). */
class IcsCalendar private constructor(val events: List<IcsEvent>) {
    companion object {
        /** Reads [text]; a time without a zone (and a zone nobody knows) is local time in [floating]. */
        fun read(text: String, floating: TimeZone): IcsCalendar {
            val roots = IcsText.parse(text)
            val calendars = roots.filter { it.name == "VCALENDAR" }.ifEmpty { roots }
            val defined = HashMap<String, IcsZone>()
            for (zone in calendars.flatMap { it.children("VTIMEZONE") }) {
                val tzid = zone.first("TZID")?.value?.trim()?.removeSurrounding("\"") ?: continue
                VTimeZone.of(zone)?.let { defined[tzid] = it }
            }
            val zones = IcsZones.Resolver(defined, PlatformIcsZone(floating))
            val components = calendars.flatMap { it.children("VEVENT") } + roots.filter { it.name == "VEVENT" }
            val masters = LinkedHashMap<String, IcsComponent>()
            val singles = mutableListOf<IcsComponent>()
            val overrides = mutableListOf<IcsComponent>()
            for (component in components) {
                val uid = component.first("UID")?.value?.trim()
                when {
                    component.first("RECURRENCE-ID") != null -> overrides += component
                    uid.isNullOrEmpty() -> singles += component
                    else -> masters[uid] = component
                }
            }
            val byMaster = HashMap<String, MutableList<IcsComponent>>()
            val orphans = mutableListOf<IcsComponent>()
            for (override in overrides) {
                val uid = override.first("UID")?.value?.trim()
                if (uid != null && uid in masters) byMaster.getOrPut(uid) { mutableListOf() } += override else orphans += override
            }
            val events = masters.map { (uid, component) -> IcsEvent(component, byMaster[uid].orEmpty(), zones) } +
                singles.map { IcsEvent(it, emptyList(), zones) } +
                orphans.map { IcsEvent(it, emptyList(), zones) }
            return IcsCalendar(events)
        }
    }
}
