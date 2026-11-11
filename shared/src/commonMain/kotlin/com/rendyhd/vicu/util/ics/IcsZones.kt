package com.rendyhd.vicu.util.ics

import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/** A zone an event's times are read in: wall-clock time to an instant and back. */
interface IcsZone {
    fun toInstant(wall: LocalDateTime): Instant
    fun toWall(instant: Instant): LocalDateTime
}

/** A zone of the platform's database (IANA), or UTC, or the device's own (floating times). */
class PlatformIcsZone(val zone: TimeZone) : IcsZone {
    override fun toInstant(wall: LocalDateTime): Instant = wall.toInstant(zone)
    override fun toWall(instant: Instant): LocalDateTime = instant.toLocalDateTime(zone)
}

/**
 * A VTIMEZONE of the file: its STANDARD and DAYLIGHT observances, each with its onset (a DTSTART in
 * the time before it, TZOFFSETFROM), an RRULE and RDATEs. The onsets are worked out once, up to
 * [UNTIL_YEAR]; the offset at an instant is the one of the last onset before it.
 */
class VTimeZone private constructor(private val onsets: List<Pair<Long, Int>>, private val initial: Int) : IcsZone {
    private val offsets = (onsets.map { it.second } + initial).distinct()

    /** Seconds the zone is ahead of UTC at [epochSeconds]. */
    fun offsetAt(epochSeconds: Long): Int {
        var low = 0
        var high = onsets.size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (onsets[mid].first <= epochSeconds) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return if (found < 0) initial else onsets[found].second
    }

    override fun toInstant(wall: LocalDateTime): Instant {
        val local = wall.toInstant(TimeZone.UTC).epochSeconds
        // The earliest instant whose offset gives this wall time (the earlier one in a clock going back).
        val valid = offsets.filter { offset -> offsetAt(local - offset) == offset }.maxOrNull()?.let { local - it }
        if (valid != null) return Instant.fromEpochSeconds(valid)
        // In a gap (the clock going forward): the offset before it, so the time lands after the gap.
        val before = offsets.minOf { offsetAt(local - it) }
        return Instant.fromEpochSeconds(local - before)
    }

    override fun toWall(instant: Instant): LocalDateTime =
        Instant.fromEpochSeconds(instant.epochSeconds + offsetAt(instant.epochSeconds)).toLocalDateTime(TimeZone.UTC)

    companion object {
        private const val UNTIL_YEAR = 2200

        private val OFFSET = Regex("""^([+-])(\d{2})(\d{2})(\d{2})?$""")

        /** `+0130` in seconds, or null. */
        fun offsetSeconds(value: String?): Int? {
            val m = OFFSET.matchEntire(value?.trim() ?: return null) ?: return null
            val (sign, h, mi, s) = m.destructured
            val seconds = h.toInt() * 3600 + mi.toInt() * 60 + (s.ifEmpty { "0" }.toInt())
            return if (sign == "-") -seconds else seconds
        }

        /** The zone a VTIMEZONE component describes, or null when it has no readable observance. */
        fun of(component: IcsComponent): VTimeZone? {
            val onsets = mutableListOf<Pair<Long, Int>>()
            var earliest: Pair<Long, Int>? = null
            val stop = LocalDateTime(UNTIL_YEAR, 1, 1, 0, 0)
            for (observance in component.children.filter { it.name == "STANDARD" || it.name == "DAYLIGHT" }) {
                val from = offsetSeconds(observance.first("TZOFFSETFROM")?.value) ?: continue
                val to = offsetSeconds(observance.first("TZOFFSETTO")?.value) ?: continue
                val start = IcsText.timeOf(observance.first("DTSTART"))?.wall ?: continue
                val walls = mutableListOf<LocalDateTime>()
                val rule = observance.first("RRULE")?.let { IcsRecur.parse(it.value, null) }
                if (rule != null) {
                    // UNTIL is UTC: as wall time before the onset.
                    val until = rule.until?.let { u ->
                        if (u.utc) Instant.fromEpochSeconds(u.wall.toInstant(TimeZone.UTC).epochSeconds + from).toLocalDateTime(TimeZone.UTC) else u.wall
                    }
                    walls += IcsRecurrence.expand(rule, start, isDate = false, until = until, stop = stop, limit = 5_000)
                } else {
                    walls += start
                }
                observance.all("RDATE").forEach { property -> IcsText.times(property).forEach { walls += it.wall } }
                for (wall in walls.distinct()) {
                    val utc = wall.toInstant(TimeZone.UTC).epochSeconds - from
                    onsets += utc to to
                    if (earliest == null || utc < earliest.first) earliest = utc to from
                }
            }
            if (onsets.isEmpty()) return null
            return VTimeZone(onsets.sortedBy { it.first }, earliest?.second ?: onsets.first().second)
        }
    }
}

/**
 * Which zone a TZID means (contract 20.9: "the file's own time zones"). The file's VTIMEZONE first,
 * as the desktop's ical.js does; else an IANA zone of the platform's database: the TZID itself,
 * the tail of a "/mozilla.org/.../Europe/Berlin" style id, or a Windows name ("W. Europe Standard
 * Time", what Outlook and Exchange write) through the same table as the desktop's
 * `src/main/calendar/timezones.ts`. A TZID that is none of these is read as floating local time.
 */
object IcsZones {
    /** Windows zone ids (CLDR windowsZones, territory 001) for the zones calendars use most. */
    private val WINDOWS_ZONES: Map<String, String> = mapOf(
        "Dateline Standard Time" to "Etc/GMT+12",
        "UTC-11" to "Etc/GMT+11",
        "Aleutian Standard Time" to "America/Adak",
        "Hawaiian Standard Time" to "Pacific/Honolulu",
        "Marquesas Standard Time" to "Pacific/Marquesas",
        "Alaskan Standard Time" to "America/Anchorage",
        "UTC-09" to "Etc/GMT+9",
        "Pacific Standard Time (Mexico)" to "America/Tijuana",
        "UTC-08" to "Etc/GMT+8",
        "Pacific Standard Time" to "America/Los_Angeles",
        "US Mountain Standard Time" to "America/Phoenix",
        "Mountain Standard Time (Mexico)" to "America/Mazatlan",
        "Mountain Standard Time" to "America/Denver",
        "Yukon Standard Time" to "America/Whitehorse",
        "Central America Standard Time" to "America/Guatemala",
        "Central Standard Time" to "America/Chicago",
        "Easter Island Standard Time" to "Pacific/Easter",
        "Central Standard Time (Mexico)" to "America/Mexico_City",
        "Canada Central Standard Time" to "America/Regina",
        "SA Pacific Standard Time" to "America/Bogota",
        "Eastern Standard Time (Mexico)" to "America/Cancun",
        "Eastern Standard Time" to "America/New_York",
        "Haiti Standard Time" to "America/Port-au-Prince",
        "Cuba Standard Time" to "America/Havana",
        "US Eastern Standard Time" to "America/Indiana/Indianapolis",
        "Turks And Caicos Standard Time" to "America/Grand_Turk",
        "Paraguay Standard Time" to "America/Asuncion",
        "Atlantic Standard Time" to "America/Halifax",
        "Venezuela Standard Time" to "America/Caracas",
        "Central Brazilian Standard Time" to "America/Cuiaba",
        "SA Western Standard Time" to "America/La_Paz",
        "Pacific SA Standard Time" to "America/Santiago",
        "Newfoundland Standard Time" to "America/St_Johns",
        "Tocantins Standard Time" to "America/Araguaina",
        "E. South America Standard Time" to "America/Sao_Paulo",
        "SA Eastern Standard Time" to "America/Cayenne",
        "Argentina Standard Time" to "America/Argentina/Buenos_Aires",
        "Greenland Standard Time" to "America/Nuuk",
        "Montevideo Standard Time" to "America/Montevideo",
        "Magallanes Standard Time" to "America/Punta_Arenas",
        "Saint Pierre Standard Time" to "America/Miquelon",
        "Bahia Standard Time" to "America/Bahia",
        "UTC-02" to "Etc/GMT+2",
        "Azores Standard Time" to "Atlantic/Azores",
        "Cape Verde Standard Time" to "Atlantic/Cape_Verde",
        "UTC" to "Etc/UTC",
        "Coordinated Universal Time" to "Etc/UTC",
        "GMT Standard Time" to "Europe/London",
        "Greenwich Standard Time" to "Atlantic/Reykjavik",
        "Sao Tome Standard Time" to "Africa/Sao_Tome",
        "Morocco Standard Time" to "Africa/Casablanca",
        "W. Europe Standard Time" to "Europe/Berlin",
        "Central Europe Standard Time" to "Europe/Budapest",
        "Romance Standard Time" to "Europe/Paris",
        "Central European Standard Time" to "Europe/Warsaw",
        "W. Central Africa Standard Time" to "Africa/Lagos",
        "Jordan Standard Time" to "Asia/Amman",
        "GTB Standard Time" to "Europe/Bucharest",
        "Middle East Standard Time" to "Asia/Beirut",
        "Egypt Standard Time" to "Africa/Cairo",
        "E. Europe Standard Time" to "Europe/Chisinau",
        "Syria Standard Time" to "Asia/Damascus",
        "West Bank Standard Time" to "Asia/Hebron",
        "South Africa Standard Time" to "Africa/Johannesburg",
        "FLE Standard Time" to "Europe/Kyiv",
        "Israel Standard Time" to "Asia/Jerusalem",
        "South Sudan Standard Time" to "Africa/Juba",
        "Kaliningrad Standard Time" to "Europe/Kaliningrad",
        "Sudan Standard Time" to "Africa/Khartoum",
        "Libya Standard Time" to "Africa/Tripoli",
        "Namibia Standard Time" to "Africa/Windhoek",
        "Arabic Standard Time" to "Asia/Baghdad",
        "Turkey Standard Time" to "Europe/Istanbul",
        "Arab Standard Time" to "Asia/Riyadh",
        "Belarus Standard Time" to "Europe/Minsk",
        "Russian Standard Time" to "Europe/Moscow",
        "E. Africa Standard Time" to "Africa/Nairobi",
        "Volgograd Standard Time" to "Europe/Volgograd",
        "Iran Standard Time" to "Asia/Tehran",
        "Arabian Standard Time" to "Asia/Dubai",
        "Astrakhan Standard Time" to "Europe/Astrakhan",
        "Azerbaijan Standard Time" to "Asia/Baku",
        "Russia Time Zone 3" to "Europe/Samara",
        "Mauritius Standard Time" to "Indian/Mauritius",
        "Saratov Standard Time" to "Europe/Saratov",
        "Georgian Standard Time" to "Asia/Tbilisi",
        "Caucasus Standard Time" to "Asia/Yerevan",
        "Afghanistan Standard Time" to "Asia/Kabul",
        "West Asia Standard Time" to "Asia/Tashkent",
        "Ekaterinburg Standard Time" to "Asia/Yekaterinburg",
        "Pakistan Standard Time" to "Asia/Karachi",
        "Qyzylorda Standard Time" to "Asia/Qyzylorda",
        "India Standard Time" to "Asia/Kolkata",
        "Sri Lanka Standard Time" to "Asia/Colombo",
        "Nepal Standard Time" to "Asia/Kathmandu",
        "Central Asia Standard Time" to "Asia/Almaty",
        "Bangladesh Standard Time" to "Asia/Dhaka",
        "Omsk Standard Time" to "Asia/Omsk",
        "Myanmar Standard Time" to "Asia/Yangon",
        "SE Asia Standard Time" to "Asia/Bangkok",
        "Altai Standard Time" to "Asia/Barnaul",
        "W. Mongolia Standard Time" to "Asia/Hovd",
        "North Asia Standard Time" to "Asia/Krasnoyarsk",
        "N. Central Asia Standard Time" to "Asia/Novosibirsk",
        "Tomsk Standard Time" to "Asia/Tomsk",
        "China Standard Time" to "Asia/Shanghai",
        "North Asia East Standard Time" to "Asia/Irkutsk",
        "Singapore Standard Time" to "Asia/Singapore",
        "W. Australia Standard Time" to "Australia/Perth",
        "Taipei Standard Time" to "Asia/Taipei",
        "Ulaanbaatar Standard Time" to "Asia/Ulaanbaatar",
        "Aus Central W. Standard Time" to "Australia/Eucla",
        "Transbaikal Standard Time" to "Asia/Chita",
        "Tokyo Standard Time" to "Asia/Tokyo",
        "North Korea Standard Time" to "Asia/Pyongyang",
        "Korea Standard Time" to "Asia/Seoul",
        "Yakutsk Standard Time" to "Asia/Yakutsk",
        "Cen. Australia Standard Time" to "Australia/Adelaide",
        "AUS Central Standard Time" to "Australia/Darwin",
        "E. Australia Standard Time" to "Australia/Brisbane",
        "AUS Eastern Standard Time" to "Australia/Sydney",
        "West Pacific Standard Time" to "Pacific/Port_Moresby",
        "Tasmania Standard Time" to "Australia/Hobart",
        "Vladivostok Standard Time" to "Asia/Vladivostok",
        "Lord Howe Standard Time" to "Australia/Lord_Howe",
        "Bougainville Standard Time" to "Pacific/Bougainville",
        "Russia Time Zone 10" to "Asia/Srednekolymsk",
        "Magadan Standard Time" to "Asia/Magadan",
        "Norfolk Standard Time" to "Pacific/Norfolk",
        "Sakhalin Standard Time" to "Asia/Sakhalin",
        "Central Pacific Standard Time" to "Pacific/Guadalcanal",
        "Russia Time Zone 11" to "Asia/Kamchatka",
        "New Zealand Standard Time" to "Pacific/Auckland",
        "UTC+12" to "Etc/GMT-12",
        "Fiji Standard Time" to "Pacific/Fiji",
        "Chatham Islands Standard Time" to "Pacific/Chatham",
        "UTC+13" to "Etc/GMT-13",
        "Tonga Standard Time" to "Pacific/Tongatapu",
        "Samoa Standard Time" to "Pacific/Apia",
        "Line Islands Standard Time" to "Pacific/Kiritimati",
    )

    /** Outlook's display names ("(UTC+01:00) Amsterdam, Berlin, ..."), by a phrase of the city list. */
    private val DISPLAY_NAMES: List<Pair<Regex, String>> = listOf(
        Regex("Amsterdam, Berlin", RegexOption.IGNORE_CASE) to "Europe/Berlin",
        Regex("Brussels, Copenhagen, Madrid, Paris", RegexOption.IGNORE_CASE) to "Europe/Paris",
        Regex("Belgrade, Bratislava, Budapest", RegexOption.IGNORE_CASE) to "Europe/Budapest",
        Regex("Sarajevo, Skopje, Warsaw", RegexOption.IGNORE_CASE) to "Europe/Warsaw",
        Regex("Dublin, Edinburgh, Lisbon, London", RegexOption.IGNORE_CASE) to "Europe/London",
        Regex("Athens, Bucharest", RegexOption.IGNORE_CASE) to "Europe/Bucharest",
        Regex("Helsinki, Kyiv|Helsinki, Kiev", RegexOption.IGNORE_CASE) to "Europe/Kyiv",
        Regex("""Moscow, St\.? Petersburg""", RegexOption.IGNORE_CASE) to "Europe/Moscow",
        Regex("""Eastern Time \(US (?:&|and) Canada\)""", RegexOption.IGNORE_CASE) to "America/New_York",
        Regex("""Central Time \(US (?:&|and) Canada\)""", RegexOption.IGNORE_CASE) to "America/Chicago",
        Regex("""Mountain Time \(US (?:&|and) Canada\)""", RegexOption.IGNORE_CASE) to "America/Denver",
        Regex("""Pacific Time \(US (?:&|and) Canada\)""", RegexOption.IGNORE_CASE) to "America/Los_Angeles",
        Regex("Canberra, Melbourne, Sydney", RegexOption.IGNORE_CASE) to "Australia/Sydney",
        Regex("Auckland, Wellington", RegexOption.IGNORE_CASE) to "Pacific/Auckland",
        Regex("Osaka, Sapporo, Tokyo", RegexOption.IGNORE_CASE) to "Asia/Tokyo",
        Regex("Beijing, Chongqing, Hong Kong", RegexOption.IGNORE_CASE) to "Asia/Shanghai",
        Regex("Chennai, Kolkata, Mumbai", RegexOption.IGNORE_CASE) to "Asia/Kolkata",
        Regex("""Coordinated Universal Time|^\(UTC\) """, RegexOption.IGNORE_CASE) to "Etc/UTC",
    )

    private val IANA_TAIL = Regex("""^[A-Za-z_]+(?:/[A-Za-z0-9_+-]+)+$|^UTC$|^GMT$""")

    private fun platformZone(name: String): TimeZone? = runCatching { TimeZone.of(name) }.getOrNull()

    /** The IANA zone a TZID means, or null when it is none Vicu knows. */
    fun ianaZoneFor(tzid: String): String? {
        val id = tzid.trim().removeSurrounding("\"")
        if (id.isEmpty()) return null
        WINDOWS_ZONES[id]?.let { return it }
        DISPLAY_NAMES.firstOrNull { (pattern, _) -> pattern.containsMatchIn(id) }?.let { return it.second }
        val parts = id.split('/').filter { it.isNotEmpty() }
        for (take in minOf(3, parts.size) downTo 1) {
            val tail = parts.takeLast(take).joinToString("/")
            if (IANA_TAIL.matches(tail) && platformZone(tail) != null) return tail
        }
        return null
    }

    /**
     * The zones of a file's TZIDs: [defined] are its own VTIMEZONEs by TZID; [floating] is the
     * device's zone, for a TZID nobody knows.
     */
    class Resolver(private val defined: Map<String, IcsZone>, private val floating: IcsZone) {
        private val cache = HashMap<String, IcsZone>()

        fun zoneOf(time: IcsTime): IcsZone = when {
            time.utc -> UTC
            time.tzid == null -> floating
            else -> cache.getOrPut(time.tzid) {
                defined[time.tzid] ?: ianaZoneFor(time.tzid)?.let(::platformZone)?.let(::PlatformIcsZone) ?: floating
            }
        }

        /** The instant of a DATE-TIME value (a DATE is its midnight in the floating zone). */
        fun instantOf(time: IcsTime): Instant = zoneOf(time).toInstant(time.wall)
    }

    private val UTC = PlatformIcsZone(TimeZone.UTC)
}
