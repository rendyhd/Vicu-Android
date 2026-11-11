package com.rendyhd.vicu.util.ics

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** A BYDAY value: `MO` (no [n]), `2TU`, `-1FR`. Weekdays are 0 (Sunday) to 6 (Saturday). */
data class IcsByDay(val n: Int?, val weekday: Int)

/** An RRULE as written, parts by name (RFC 5545 3.3.10). */
data class IcsRecur(
    /** FREQ, upper case: DAILY, WEEKLY, MONTHLY, YEARLY, HOURLY ... */
    val freq: String,
    /** INTERVAL, 1 when absent or not a positive number. */
    val interval: Int,
    val count: Int?,
    val until: IcsTime?,
    /** BYDAY values as written (upper case). */
    val byDayText: List<String>,
    val byMonthDay: List<Int>,
    val byMonth: List<Int>,
    val bySetPos: List<Int>,
    val byYearDay: List<Int>,
    val byWeekNo: List<Int>,
    val byHour: List<Int>,
    val byMinute: List<Int>,
    val bySecond: List<Int>,
    /** WKST as written, null when absent. */
    val wkst: String?,
    /** The names of the BY parts other than BYDAY, BYMONTHDAY, BYMONTH and BYSETPOS that have values. */
    val otherParts: List<String>,
) {
    /** The BYDAY values that read; an unreadable one is left out of the expansion. */
    val byDay: List<IcsByDay> get() = byDayText.mapNotNull(IcsRecurrence::readByDay)

    companion object {
        private val MAPPED_PARTS = setOf("BYDAY", "BYMONTHDAY", "BYMONTH", "BYSETPOS")

        /** An RRULE value; [startTzid] is the zone an UNTIL without Z is read in. Null without a FREQ. */
        fun parse(value: String, startTzid: String?): IcsRecur? {
            val parts = LinkedHashMap<String, String>()
            for (part in value.trim().split(';')) {
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                parts[part.substring(0, eq).trim().uppercase()] = part.substring(eq + 1).trim()
            }
            val freq = parts["FREQ"]?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
            fun numbers(name: String): List<Int> =
                parts[name]?.split(',')?.mapNotNull { it.trim().removePrefix("+").toIntOrNull() }.orEmpty()
            return IcsRecur(
                freq = freq,
                interval = parts["INTERVAL"]?.toIntOrNull()?.takeIf { it > 0 } ?: 1,
                count = parts["COUNT"]?.toIntOrNull(),
                until = parts["UNTIL"]?.let { IcsText.time(it, startTzid) },
                byDayText = parts["BYDAY"]?.split(',')?.map { it.trim().uppercase() }?.filter { it.isNotEmpty() }.orEmpty(),
                byMonthDay = numbers("BYMONTHDAY"),
                byMonth = numbers("BYMONTH"),
                bySetPos = numbers("BYSETPOS"),
                byYearDay = numbers("BYYEARDAY"),
                byWeekNo = numbers("BYWEEKNO"),
                byHour = numbers("BYHOUR"),
                byMinute = numbers("BYMINUTE"),
                bySecond = numbers("BYSECOND"),
                wkst = parts["WKST"]?.uppercase(),
                otherParts = parts.filter { (name, v) -> name.startsWith("BY") && name !in MAPPED_PARTS && v.isNotBlank() }.keys.toList(),
            )
        }
    }
}

/**
 * Expanding an RRULE into its instances (RFC 5545 3.3.10 and 3.8.5.3), in the wall-clock time of the
 * event's own zone. Every period of the rule (a year, month, week or day, stepped by INTERVAL) gets
 * its candidate days, kept when they pass every BY part, then BYSETPOS picks among them. DTSTART is
 * always the first instance and counts towards COUNT. Bounded: a rule that gives nothing (the 30th of
 * February) stops at [IcsRecurrence.expand]'s `stop`, and no rule gives more than its limit.
 */
object IcsRecurrence {
    private val CODES = listOf("SU", "MO", "TU", "WE", "TH", "FR", "SA")
    private val BY_DAY = Regex("""^([+-]?\d{1,2})?(SU|MO|TU|WE|TH|FR|SA)$""")
    private const val MAX_PERIODS = 200_000

    fun readByDay(value: String): IcsByDay? {
        val m = BY_DAY.matchEntire(value.trim().uppercase()) ?: return null
        val n = m.groupValues[1].takeIf { it.isNotEmpty() }?.removePrefix("+")?.toInt()
        return IcsByDay(n, CODES.indexOf(m.groupValues[2]))
    }

    /** 0 is Sunday ... 6 is Saturday. */
    fun weekdayOf(day: LocalDate): Int = day.dayOfWeek.isoDayNumber % 7

    private fun daysInMonth(year: Int, month: Int): Int =
        LocalDate(year, month, 1).plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY).day

    private fun daysInYear(year: Int): Int = if (LocalDate(year, 12, 31).dayOfYear == 366) 366 else 365

    /** The first day of week 1 of [year]: the week (from [wkst]) holding at least four days of the year. */
    private fun weekOneStart(year: Int, wkst: Int): LocalDate {
        val jan1 = LocalDate(year, 1, 1)
        val offset = (weekdayOf(jan1) - wkst + 7) % 7
        return if (offset <= 3) jan1.minus(offset, DateTimeUnit.DAY) else jan1.plus(7 - offset, DateTimeUnit.DAY)
    }

    private fun epochSeconds(wall: LocalDateTime): Long = wall.toInstant(TimeZone.UTC).epochSeconds

    private fun wallOf(seconds: Long): LocalDateTime = Instant.fromEpochSeconds(seconds).toLocalDateTime(TimeZone.UTC)

    /**
     * The instances of [rule] from [start] (DTSTART, first), while before [stop] and not after [until]
     * (both wall-clock in the event's zone), at most [limit]. With [isDate] every instance is midnight
     * and BYHOUR, BYMINUTE and BYSECOND are ignored.
     */
    fun expand(
        rule: IcsRecur,
        start: LocalDateTime,
        isDate: Boolean,
        until: LocalDateTime?,
        stop: LocalDateTime,
        limit: Int = 20_000,
    ): List<LocalDateTime> {
        val out = mutableListOf(start)
        val count = rule.count?.coerceAtLeast(1)
        val max = minOf(limit, count ?: Int.MAX_VALUE)
        if (out.size >= max) return out

        // Instances after DTSTART, in order; returns false once the rule has ended.
        fun take(candidate: LocalDateTime): Boolean {
            if (candidate <= start) return true
            if (until != null && candidate > until) return false
            if (candidate >= stop) return false
            out += candidate
            return out.size < max
        }

        when (rule.freq) {
            "YEARLY", "MONTHLY", "WEEKLY", "DAILY" -> expandByDays(rule, start, isDate, stop, ::take)
            "HOURLY", "MINUTELY", "SECONDLY" -> if (!isDate) expandByTime(rule, start, stop, ::take)
        }
        return out
    }

    private fun expandByDays(
        rule: IcsRecur,
        start: LocalDateTime,
        isDate: Boolean,
        stop: LocalDateTime,
        take: (LocalDateTime) -> Boolean,
    ) {
        val wkst = CODES.indexOf(rule.wkst ?: "MO").takeIf { it >= 0 } ?: 1
        val byDay = rule.byDay
        val noDayParts = rule.byWeekNo.isEmpty() && rule.byYearDay.isEmpty() && rule.byMonthDay.isEmpty() && byDay.isEmpty()
        var months = rule.byMonth
        var monthDays = rule.byMonthDay
        var days = byDay
        if (noDayParts) {
            when (rule.freq) {
                "YEARLY" -> {
                    if (months.isEmpty()) months = listOf(start.month.number)
                    monthDays = listOf(start.day)
                }
                "MONTHLY" -> monthDays = listOf(start.day)
                "WEEKLY" -> days = listOf(IcsByDay(null, weekdayOf(start.date)))
            }
        }
        val times: List<LocalTime> = if (isDate) {
            listOf(LocalTime(0, 0))
        } else {
            val hours = rule.byHour.filter { it in 0..23 }.ifEmpty { listOf(start.hour) }
            val minutes = rule.byMinute.filter { it in 0..59 }.ifEmpty { listOf(start.minute) }
            val seconds = rule.bySecond.filter { it in 0..59 }.ifEmpty { listOf(start.second) }
            hours.flatMap { h -> minutes.flatMap { m -> seconds.map { s -> LocalTime(h, m, s) } } }.sorted()
        }
        val weekStart = start.date.minus((weekdayOf(start.date) - wkst + 7) % 7, DateTimeUnit.DAY)

        for (period in 0 until MAX_PERIODS) {
            val step = period.toLong() * rule.interval
            if (step > Int.MAX_VALUE) return
            var periodYear = 0
            val periodDays: List<LocalDate> = when (rule.freq) {
                "YEARLY" -> {
                    periodYear = start.year + step.toInt()
                    if (periodYear > 9999) return
                    if (rule.byWeekNo.isEmpty() && months.isNotEmpty()) {
                        // Only the months it can give, so a rule from 1601 (a time zone's) stays quick.
                        months.filter { it in 1..12 }.distinct().sorted().flatMap { m ->
                            List(daysInMonth(periodYear, m)) { LocalDate(periodYear, m, it + 1) }
                        }
                    } else {
                        val first = if (rule.byWeekNo.isNotEmpty()) weekOneStart(periodYear, wkst) else LocalDate(periodYear, 1, 1)
                        val end = if (rule.byWeekNo.isNotEmpty()) weekOneStart(periodYear + 1, wkst) else LocalDate(periodYear + 1, 1, 1)
                        generateSequence(first) { it.plus(1, DateTimeUnit.DAY) }.takeWhile { it < end }.toList()
                    }
                }
                "MONTHLY" -> {
                    val first = LocalDate(start.year, start.month.number, 1).plus(step.toInt(), DateTimeUnit.MONTH)
                    if (first.year > 9999) return
                    List(daysInMonth(first.year, first.month.number)) { first.plus(it, DateTimeUnit.DAY) }
                }
                "WEEKLY" -> {
                    val first = weekStart.plus((step * 7).toInt(), DateTimeUnit.DAY)
                    List(7) { first.plus(it, DateTimeUnit.DAY) }
                }
                else -> listOf(start.date.plus(step.toInt(), DateTimeUnit.DAY))
            }
            if (periodDays.isEmpty()) continue
            if (periodDays.first() > stop.date) return
            val matching = periodDays.filter { day ->
                matches(day, rule, months, monthDays, days, wkst, periodYear)
            }
            var candidates = matching.flatMap { day -> times.map { LocalDateTime(day, it) } }
            if (rule.bySetPos.isNotEmpty() && candidates.isNotEmpty()) {
                val size = candidates.size
                candidates = rule.bySetPos.mapNotNull { pos ->
                    val index = if (pos > 0) pos - 1 else size + pos
                    candidates.getOrNull(index)?.takeIf { pos != 0 }
                }.distinct().sorted()
            }
            for (candidate in candidates) {
                if (!take(candidate)) return
            }
        }
    }

    private fun matches(
        day: LocalDate,
        rule: IcsRecur,
        months: List<Int>,
        monthDays: List<Int>,
        days: List<IcsByDay>,
        wkst: Int,
        periodYear: Int,
    ): Boolean {
        val month = day.month.number
        if (months.isNotEmpty() && month !in months) return false
        if (rule.freq == "YEARLY" && rule.byWeekNo.isNotEmpty()) {
            val one = weekOneStart(periodYear, wkst)
            val weeks = ((weekOneStart(periodYear + 1, wkst).toEpochDays() - one.toEpochDays()) / 7).toInt()
            val number = ((day.toEpochDays() - one.toEpochDays()) / 7).toInt() + 1
            if (number !in rule.byWeekNo && (number - weeks - 1) !in rule.byWeekNo) return false
        }
        if (rule.byYearDay.isNotEmpty()) {
            val n = day.dayOfYear
            if (n !in rule.byYearDay && (n - daysInYear(day.year) - 1) !in rule.byYearDay) return false
        }
        val dim = daysInMonth(day.year, month)
        if (monthDays.isNotEmpty()) {
            if (day.day !in monthDays && (day.day - dim - 1) !in monthDays) return false
        }
        if (days.isNotEmpty()) {
            val weekday = weekdayOf(day)
            val inMonth = rule.freq == "MONTHLY" || (rule.freq == "YEARLY" && months.isNotEmpty())
            val numbered = rule.freq == "MONTHLY" || (rule.freq == "YEARLY" && rule.byWeekNo.isEmpty())
            val ok = days.any { by ->
                if (by.weekday != weekday) return@any false
                val n = by.n
                if (n == null || n == 0 || !numbered) return@any true
                if (inMonth) {
                    if (n > 0) (day.day - 1) / 7 + 1 == n else (dim - day.day) / 7 + 1 == -n
                } else {
                    val doy = day.dayOfYear
                    if (n > 0) (doy - 1) / 7 + 1 == n else (daysInYear(day.year) - doy) / 7 + 1 == -n
                }
            }
            if (!ok) return false
        }
        return true
    }

    /** HOURLY, MINUTELY and SECONDLY: steps of time, the BY parts as limits. */
    private fun expandByTime(rule: IcsRecur, start: LocalDateTime, stop: LocalDateTime, take: (LocalDateTime) -> Boolean) {
        val unit = when (rule.freq) {
            "HOURLY" -> 3_600L
            "MINUTELY" -> 60L
            else -> 1L
        }
        val from = epochSeconds(start)
        val end = epochSeconds(stop)
        val byDay = rule.byDay
        for (i in 1 until MAX_PERIODS) {
            val seconds = from + i * unit * rule.interval
            if (seconds >= end) return
            val at = wallOf(seconds)
            val day = at.date
            val month = day.month.number
            if (rule.byMonth.isNotEmpty() && month !in rule.byMonth) continue
            val dim = daysInMonth(day.year, month)
            if (rule.byMonthDay.isNotEmpty() && day.day !in rule.byMonthDay && (day.day - dim - 1) !in rule.byMonthDay) continue
            if (rule.byYearDay.isNotEmpty() && day.dayOfYear !in rule.byYearDay) continue
            if (byDay.isNotEmpty() && byDay.none { it.weekday == weekdayOf(day) }) continue
            if (rule.byHour.isNotEmpty() && at.hour !in rule.byHour) continue
            if (rule.byMinute.isNotEmpty() && at.minute !in rule.byMinute) continue
            if (rule.bySecond.isNotEmpty() && at.second !in rule.bySecond) continue
            if (!take(at)) return
        }
    }
}
