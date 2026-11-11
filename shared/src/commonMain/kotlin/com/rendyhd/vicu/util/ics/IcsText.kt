package com.rendyhd.vicu.util.ics

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/** One property of a component: its name (upper case), its parameters and its raw value. */
class IcsProperty(val name: String, val params: Map<String, String>, val value: String) {
    fun param(name: String): String? = params[name.uppercase()]
}

/** A component (VCALENDAR, VEVENT, VTIMEZONE ...) with its properties and the components inside it. */
class IcsComponent(val name: String) {
    val properties = mutableListOf<IcsProperty>()
    val children = mutableListOf<IcsComponent>()

    fun first(name: String): IcsProperty? = properties.firstOrNull { it.name == name }
    fun all(name: String): List<IcsProperty> = properties.filter { it.name == name }
    fun text(name: String): String? = first(name)?.let { IcsText.unescape(it.value) }
    fun children(name: String): List<IcsComponent> = children.filter { it.name == name }
}

/** A DATE or DATE-TIME value: a date, or a wall-clock time that is UTC, in a TZID, or floating. */
data class IcsTime(
    val date: LocalDate,
    /** Null for a DATE value. */
    val time: LocalTime?,
    val utc: Boolean,
    /** The TZID parameter, without quotes; null for UTC, floating and dates. */
    val tzid: String?,
) {
    val isDate: Boolean get() = time == null
    val wall: LocalDateTime get() = LocalDateTime(date, time ?: LocalTime(0, 0))
}

/**
 * Reading iCalendar text (RFC 5545) into components: lines unfolded, properties split into name,
 * parameters and value, BEGIN and END nested. Lenient: CRLF or LF, a BOM, unknown components and
 * broken lines are skipped rather than failing the file.
 */
object IcsText {
    /** The top-level components of [text] (usually one VCALENDAR). */
    fun parse(text: String): List<IcsComponent> {
        val roots = mutableListOf<IcsComponent>()
        val stack = ArrayDeque<IcsComponent>()
        for (line in unfold(text)) {
            val property = parseLine(line) ?: continue
            when (property.name) {
                "BEGIN" -> {
                    val component = IcsComponent(property.value.trim().uppercase())
                    val parent = stack.lastOrNull()
                    if (parent == null) roots += component else parent.children += component
                    stack.addLast(component)
                }
                "END" -> {
                    val name = property.value.trim().uppercase()
                    // Close up to the matching BEGIN; an END without one is ignored.
                    if (stack.any { it.name == name }) {
                        while (stack.isNotEmpty()) {
                            if (stack.removeLast().name == name) break
                        }
                    }
                }
                else -> stack.lastOrNull()?.properties?.add(property)
            }
        }
        return roots
    }

    /** The logical lines: a line that starts with a space or a tab continues the one before it. */
    fun unfold(text: String): List<String> {
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        var started = false
        for (raw in text.removePrefix("﻿").split('\n')) {
            val line = raw.removeSuffix("\r")
            if (started && (line.startsWith(" ") || line.startsWith("\t"))) {
                current.append(line.substring(1))
                continue
            }
            if (started) lines += current.toString()
            current.clear()
            current.append(line)
            started = true
        }
        if (started) lines += current.toString()
        return lines.filter { it.isNotBlank() }
    }

    /** `NAME;PARAM=a;OTHER="b:c":value`, or null when the line has no colon. */
    fun parseLine(line: String): IcsProperty? {
        var i = 0
        var inQuotes = false
        var nameEnd = -1
        var valueStart = -1
        while (i < line.length) {
            val c = line[i]
            if (c == '"') inQuotes = !inQuotes
            if (!inQuotes) {
                if (c == ';' && nameEnd < 0) nameEnd = i
                if (c == ':') {
                    valueStart = i + 1
                    if (nameEnd < 0) nameEnd = i
                    break
                }
            }
            i++
        }
        if (valueStart < 0) return null
        val name = line.substring(0, nameEnd).trim().uppercase()
        if (name.isEmpty()) return null
        val params = LinkedHashMap<String, String>()
        if (nameEnd < valueStart - 1) {
            for (part in splitOutsideQuotes(line.substring(nameEnd + 1, valueStart - 1), ';')) {
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                params[part.substring(0, eq).trim().uppercase()] = part.substring(eq + 1).trim().removeSurrounding("\"")
            }
        }
        return IcsProperty(name, params, line.substring(valueStart))
    }

    private fun splitOutsideQuotes(text: String, separator: Char): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        for (c in text) {
            if (c == '"') inQuotes = !inQuotes
            if (c == separator && !inQuotes) {
                parts += current.toString()
                current.clear()
            } else {
                current.append(c)
            }
        }
        parts += current.toString()
        return parts
    }

    /** A TEXT value with its escapes read: `\n`, `\,`, `\;`, `\\`. */
    fun unescape(value: String): String {
        if ('\\' !in value) return value
        val out = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (val next = value[i + 1]) {
                    'n', 'N' -> out.append('\n')
                    else -> out.append(next)
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private val DATE = Regex("""^(\d{4})(\d{2})(\d{2})$""")
    private val DATE_TIME = Regex("""^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})?(Z)?$""")

    /** One DATE or DATE-TIME value; [tzid] applies to a time without Z. Null when it does not read. */
    fun time(value: String, tzid: String?): IcsTime? {
        val text = value.trim().uppercase()
        DATE.matchEntire(text)?.let { m ->
            val (y, mo, d) = m.destructured
            val date = runCatching { LocalDate(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull() ?: return null
            return IcsTime(date, null, utc = false, tzid = null)
        }
        val m = DATE_TIME.matchEntire(text) ?: return null
        val g = m.groupValues
        return runCatching {
            val date = LocalDate(g[1].toInt(), g[2].toInt(), g[3].toInt())
            // A leap second (60) is read as 59.
            val time = LocalTime(g[4].toInt(), g[5].toInt(), (g[6].ifEmpty { "0" }.toInt()).coerceAtMost(59))
            val utc = g[7] == "Z"
            IcsTime(date, time, utc, if (utc) null else tzid?.takeIf { it.isNotBlank() })
        }.getOrNull()
    }

    /** The values of a DATE / DATE-TIME property (EXDATE and RDATE may hold several). */
    fun times(property: IcsProperty): List<IcsTime> {
        val tzid = property.param("TZID")
        return property.value.split(',').mapNotNull { part ->
            // A PERIOD (start/end) is read by its start.
            time(part.substringBefore('/'), tzid)
        }
    }

    /** The first value of a DATE / DATE-TIME property. */
    fun timeOf(property: IcsProperty?): IcsTime? = property?.let { times(it).firstOrNull() }

    private val DURATION = Regex("""^([+-])?P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$""")

    /** A DURATION in seconds, or null when it does not read. */
    fun durationSeconds(value: String): Long? {
        val m = DURATION.matchEntire(value.trim().uppercase()) ?: return null
        val g = m.groupValues
        if (g.drop(2).all { it.isEmpty() }) return null
        fun n(i: Int) = g[i].ifEmpty { "0" }.toLong()
        val seconds = n(2) * 7 * 86_400 + n(3) * 86_400 + n(4) * 3_600 + n(5) * 60 + n(6)
        return if (g[1] == "-") -seconds else seconds
    }
}
