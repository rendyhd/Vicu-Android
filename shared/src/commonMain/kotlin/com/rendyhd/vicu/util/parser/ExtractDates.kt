package com.rendyhd.vicu.util.parser

import com.rendyhd.vicu.util.DueDates
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Date extraction for the quick-add parser. Implements section 5.1 of
 * docs/cross-app-semantics-v1.md and the date rules of shared-parser-spec.md; the shared corpus
 * (`test-fixtures/nlp-corpus-v1.json`) is the test. Desktop uses chrono-node for the same job,
 * so this file does the whole of it by hand:
 *
 * - a text is cut into date atoms (a day, a weekday, a month-name date, a slash date, ...) and
 *   time atoms (`3pm`, `at 14:00`), and a date next to a time becomes one phrase;
 * - a date-only phrase is judged against the reference *day*, never against the clock, so
 *   "tuesday" typed on a Tuesday evening is today and not next week;
 * - three-letter weekday abbreviations only count after on/next/this/by/due or before a time;
 * - the connectors on/by/due (and `at` before a time) go with the date they introduce;
 * - slash dates follow the locale's day/month order;
 * - "now" is never a date.
 */

/**
 * [hasTime] says whether the text named a time of day ("3pm"). Without one, [dueDate] carries the
 * date-only time of day (23:59:59) and callers store the date as date-only (see DueDates).
 */
data class DateResult(
    val dueDate: LocalDateTime?,
    val tokens: List<ParsedToken>,
    val hasTime: Boolean = false,
)

enum class BangForm { NONE, STANDALONE, LEADING, TRAILING }

data class BangTodayResult(
    val title: String,
    val dueDate: LocalDateTime?,
    val form: BangForm,
)

/** What a date extraction needs to know besides the text. */
data class DateOptions(
    /** "Now" in [zone]: relative phrases count from it, and date-only phrases from its date. */
    val reference: LocalDateTime,
    val zone: TimeZone,
    /** True when slash dates are day/month ("15/10"), false when month/day. */
    val dayFirst: Boolean,
    /** Ignore phrases that name only a time ("10am"): used to look for a date elsewhere. */
    val skipTimeOnly: Boolean = false,
    /** Take the phrase that overlaps this region (the weekday of "every monday") before any other. */
    val prefer: IntRange? = null,
)

/** The local date and time on the device clock in [zone]. */
internal fun systemNow(zone: TimeZone = TimeZone.currentSystemDefault()): LocalDateTime =
    Clock.System.now().toLocalDateTime(zone)

/** A date without a time of day: carried at the date-only time of day, local 23:59:59. */
private fun LocalDate.dateOnly(): LocalDateTime = LocalDateTime(this, DueDates.DATE_ONLY_TIME)

private fun LocalDate.plusDays(n: Int): LocalDate = this.plus(n, DateTimeUnit.DAY)

/**
 * Extract a date from [input]: the first phrase of a date, a time, or both. [consumed] holds the
 * regions other extractors already took (they are invisible here); on success the phrase's region
 * is added to it. A phrase that names only a time means today when that time is still ahead and
 * tomorrow otherwise.
 */
fun extractDate(
    input: String,
    consumed: MutableList<IntRange>,
    options: DateOptions,
): DateResult {
    val working = buildWorkingText(input, consumed)
    val atoms = findAtoms(working, consumed, options)
    val phrases = mergeAtoms(atoms, working, consumed)

    val usable = phrases.filter { !options.skipTimeOnly || it.namesDate }
    val prefer = options.prefer
    val chosen = prefer?.let { region -> usable.firstOrNull { it.start <= region.last && it.end > region.first } }
        ?: usable.firstOrNull()
        ?: return DateResult(null, emptyList())

    val resolved = resolve(chosen, options)

    // Connector words directly before the phrase go with it: "report by friday", "taxes due
    // tomorrow". `at` goes with a time and is part of it already; on/by/due may still precede one
    // ("submit by 5pm").
    var start = chosen.start
    val connectorMatch = CONNECTOR_BEFORE_RE.find(working.substring(0, start))
    if (connectorMatch != null) {
        val extended = start - connectorMatch.groupValues[1].length
        if (!overlapsConsumed(extended, chosen.end, consumed)) start = extended
    }

    val end = chosen.end
    consumed.add(start until end)
    val token = ParsedToken(
        type = TokenType.DATE,
        start = start,
        end = end,
        value = resolved.dateTime,
        raw = input.substring(start, end),
    )
    return DateResult(resolved.dateTime, listOf(token), hasTime = resolved.hasTime)
}

/**
 * Extract the `!` -> today shortcut. Independent of the NLP parser: [TaskParser.parse] applies
 * it whether the parser is enabled or not.
 *
 * Matches:
 * - a standalone `!`;
 * - a trailing `!`: "call dentist !" or "call dentist!";
 * - a leading `!`: "! call dentist" or "!call dentist", but not when a priority token follows
 *   (`!1`, `!low`, `!med`, `!medium`, `!high`, `!urgent`, `!critical`).
 *
 * A `!` inside the text ("Hello! world") is not a date. The date is [reference]'s day,
 * date-only.
 */
fun extractBangToday(input: String, reference: LocalDateTime = systemNow()): BangTodayResult {
    val trimmed = input.trim()
    val today = reference.date.dateOnly()

    // Standalone `!`
    if (trimmed == "!") {
        return BangTodayResult("", today, BangForm.STANDALONE)
    }

    // Trailing `!` at end of string
    val trailingMatch = Regex("""^(.+?)\s*!$""").find(trimmed)
    if (trailingMatch != null) {
        return BangTodayResult(trailingMatch.groupValues[1].trim(), today, BangForm.TRAILING)
    }

    // Leading `!` at start, but NOT if followed by a priority token
    val leadingMatch = Regex("""^!\s*(.+)$""").find(trimmed)
    if (leadingMatch != null) {
        val rest = leadingMatch.groupValues[1]
        val isPriorityToken = Regex("""^[1-4](?:\s|$)""").containsMatchIn(rest) ||
            Regex("""^(?:urgent|critical|high|medium|med|low)(?:\s|$)""", RegexOption.IGNORE_CASE).containsMatchIn(rest)
        if (!isPriorityToken) {
            return BangTodayResult(rest.trim(), today, BangForm.LEADING)
        }
    }

    return BangTodayResult(input, null, BangForm.NONE)
}

private fun buildWorkingText(input: String, consumed: List<IntRange>): String {
    val chars = input.toCharArray()
    for (c in consumed) {
        for (i in c) {
            if (i in chars.indices) chars[i] = ' '
        }
    }
    return String(chars)
}

private fun overlapsConsumed(start: Int, end: Int, consumed: List<IntRange>): Boolean =
    consumed.any { start < it.last + 1 && end > it.first }

// --- Atoms and phrases ---------------------------------------------------------------------------

private enum class AtomKind { DATE, TIME, RELATIVE }

/** One piece of a date phrase, with the region of the text it covers. */
private class Atom(
    val kind: AtomKind,
    val start: Int,
    val end: Int,
    /** A DATE atom: the day. */
    val date: LocalDate? = null,
    /** A TIME atom: the time of day. */
    val time: LocalTime? = null,
    /** A RELATIVE atom ("in 2 hours"): the exact moment. */
    val moment: LocalDateTime? = null,
    /**
     * A bare or "this" weekday: today counts as its next occurrence, unless a time that has
     * already passed today goes with it (then it means next week).
     */
    val movesWhenTimePassed: Boolean = false,
)

/** One or two atoms next to each other: a date, a time, or a date and a time. */
private class Phrase(val atoms: List<Atom>) {
    val start: Int get() = atoms.first().start
    val end: Int get() = atoms.last().end

    /** True when the phrase names a day (a bare time or "in 2 hours" does not). */
    val namesDate: Boolean get() = atoms.any { it.kind == AtomKind.DATE }
}

private class Resolved(val dateTime: LocalDateTime, val hasTime: Boolean)

private fun mergeAtoms(atoms: List<Atom>, working: String, consumed: List<IntRange>): List<Phrase> {
    val phrases = mutableListOf<Phrase>()
    var i = 0
    while (i < atoms.size) {
        val first = atoms[i]
        val second = atoms.getOrNull(i + 1)
        if (second != null && canMerge(first, second, working, consumed)) {
            phrases += Phrase(listOf(first, second))
            i += 2
        } else {
            phrases += Phrase(listOf(first))
            i++
        }
    }
    return phrases
}

/**
 * A date and a time next to each other are one phrase, in either order ("tomorrow 3pm", "3pm
 * tomorrow", "3pm on friday"). Text another extractor took between them keeps them apart.
 */
private fun canMerge(first: Atom, second: Atom, working: String, consumed: List<IntRange>): Boolean {
    if (overlapsConsumed(first.end, second.start, consumed)) return false
    val gap = working.substring(first.end, second.start)
    return when {
        first.kind == AtomKind.DATE && second.kind == AtomKind.TIME -> GAP_DATE_THEN_TIME_RE.matches(gap)
        first.kind == AtomKind.TIME && second.kind == AtomKind.DATE -> GAP_TIME_THEN_DATE_RE.matches(gap)
        else -> false
    }
}

private fun resolve(phrase: Phrase, options: DateOptions): Resolved {
    val reference = options.reference
    val today = reference.date

    phrase.atoms.firstOrNull { it.kind == AtomKind.RELATIVE }?.let { return Resolved(it.moment!!, true) }

    val dateAtom = phrase.atoms.firstOrNull { it.kind == AtomKind.DATE }
    val time = phrase.atoms.firstOrNull { it.kind == AtomKind.TIME }?.time

    return when {
        dateAtom != null && time != null -> {
            var date = dateAtom.date!!
            if (dateAtom.movesWhenTimePassed && date == today && time < reference.time) date = date.plusDays(7)
            Resolved(LocalDateTime(date, time), true)
        }
        dateAtom != null -> Resolved(dateAtom.date!!.dateOnly(), false)
        else -> {
            // A time without a date: today if it is still ahead, otherwise tomorrow.
            val date = if (time!! >= reference.time) today else today.plusDays(1)
            Resolved(LocalDateTime(date, time), true)
        }
    }
}

private fun findAtoms(working: String, consumed: List<IntRange>, options: DateOptions): List<Atom> {
    val today = options.reference.date
    val atoms = mutableListOf<Atom>()

    // "in 2 hours" / "in 30 minutes": the exact moment, counted in real time (not wall-clock
    // time, so a clock change in between is accounted for).
    for (m in RELATIVE_TIME_RE.findAll(working)) {
        val n = amountOf(m.groupValues[1]) ?: continue
        val length = if (m.groupValues[2].lowercase().startsWith("h")) n.hours else n.minutes
        val moment = runCatching {
            (options.reference.toInstant(options.zone) + length).toLocalDateTime(options.zone)
        }.getOrNull() ?: continue
        atoms += Atom(AtomKind.RELATIVE, m.range.first, m.range.last + 1, moment = moment)
    }

    // "in 3 days" / "in 2 weeks" (also months and years): date-only.
    for (m in RELATIVE_DATE_RE.findAll(working)) {
        val n = amountOf(m.groupValues[1]) ?: continue
        val date = runCatching {
            when (m.groupValues[2].lowercase().trimEnd('s')) {
                "day" -> today.plus(n, DateTimeUnit.DAY)
                "week" -> today.plus(n, DateTimeUnit.WEEK)
                "month" -> today.plus(n, DateTimeUnit.MONTH)
                else -> today.plus(n, DateTimeUnit.YEAR)
            }
        }.getOrNull() ?: continue
        atoms += dateAtom(m, date)
    }

    for (m in TODAY_TOMORROW_RE.findAll(working)) {
        val date = if (m.groupValues[1].equals("today", ignoreCase = true)) today else today.plusDays(1)
        atoms += dateAtom(m, date)
    }

    // "next week" is the Monday of the following week; "next month" the same day next month.
    for (m in NEXT_PERIOD_RE.findAll(working)) {
        val date = if (m.groupValues[1].equals("week", ignoreCase = true)) {
            DueDates.nextWeekStart(today)
        } else {
            runCatching { today.plus(1, DateTimeUnit.MONTH) }.getOrNull() ?: continue
        }
        atoms += dateAtom(m, date)
    }

    for (m in WEEKDAY_RE.findAll(working)) {
        val modifier = m.groupValues[1].lowercase()
        val name = m.groupValues[2].lowercase()
        // An abbreviation is a plain word ("sun cream", "we sat on") unless something marks it.
        if (name !in FULL_WEEKDAY_NAMES && modifier.isEmpty()) {
            val before = working.substring(0, m.range.first)
            val after = working.substring(m.range.last + 1)
            if (!ABBREVIATION_PREFIX_RE.containsMatchIn(before) && !TIME_AFTER_RE.containsMatchIn(after)) continue
        }
        val target = WEEKDAY_BY_PREFIX[name.take(3)] ?: continue
        val date = if (modifier == "next") {
            DueDates.nextWeekStart(today).plusDays(target.isoDayNumber - 1)
        } else {
            today.plusDays((target.isoDayNumber - today.dayOfWeek.isoDayNumber + 7) % 7)
        }
        atoms += Atom(
            AtomKind.DATE, m.range.first, m.range.last + 1,
            date = date, movesWhenTimePassed = modifier != "next",
        )
    }

    // "jan 15", "march 3rd", "jan 15 2027"
    for (m in MONTH_DAY_RE.findAll(working)) {
        val date = monthDate(m.groupValues[1], m.groupValues[2], m.groupValues[3], today) ?: continue
        atoms += dateAtom(m, date)
    }

    // "15 jan", "3rd of march", "15 jan 2027"
    for (m in DAY_MONTH_RE.findAll(working)) {
        val date = monthDate(m.groupValues[2], m.groupValues[1], m.groupValues[3], today) ?: continue
        atoms += dateAtom(m, date)
    }

    // "2026-10-15"
    for (m in ISO_RE.findAll(working)) {
        val date = runCatching {
            LocalDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }.getOrNull() ?: continue
        atoms += dateAtom(m, date)
    }

    // "10/15", "15/10", "10/15/2026"
    for (m in SLASH_RE.findAll(working)) {
        val date = slashDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3], options.dayFirst, today)
            ?: continue
        atoms += dateAtom(m, date)
    }

    // "3:30pm", "14:00", "at 14:00"
    for (m in CLOCK_RE.findAll(working)) {
        val time = timeOf(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3]) ?: continue
        atoms += Atom(AtomKind.TIME, m.range.first, m.range.last + 1, time = time)
    }

    // "3pm", "at 3 pm"
    for (m in HOUR_RE.findAll(working)) {
        val time = timeOf(m.groupValues[1].toInt(), 0, m.groupValues[2]) ?: continue
        atoms += Atom(AtomKind.TIME, m.range.first, m.range.last + 1, time = time)
    }

    // Left to right; where two atoms overlap the earlier (then the longer) one wins. Text that
    // another extractor took is never part of a date.
    val sorted = atoms
        .filter { !overlapsConsumed(it.start, it.end, consumed) }
        .sortedWith(compareBy<Atom> { it.start }.thenByDescending { it.end })
    val selected = mutableListOf<Atom>()
    var lastEnd = 0
    for (atom in sorted) {
        if (atom.start >= lastEnd) {
            selected += atom
            lastEnd = atom.end
        }
    }
    return selected
}

private fun dateAtom(match: MatchResult, date: LocalDate): Atom =
    Atom(AtomKind.DATE, match.range.first, match.range.last + 1, date = date)

private const val MAX_AMOUNT = 100_000L

/** The N of "in N days": a number, or "a"/"an" for one. */
private fun amountOf(word: String): Long? =
    if (word.first().isDigit()) word.toLongOrNull()?.takeIf { it <= MAX_AMOUNT } else 1L

/**
 * A date named by month and day. Without a year it is the next such date (the one in the
 * reference year, or the following year when that has passed); today stays today.
 */
private fun monthDate(monthWord: String, dayWord: String, yearWord: String, today: LocalDate): LocalDate? {
    val month = MONTH_BY_PREFIX[monthWord.lowercase().take(3)] ?: return null
    val day = dayWord.toIntOrNull() ?: return null
    return datedFrom(month, day, yearWord.toIntOrNull(), today)
}

/**
 * A slash date. The locale decides the order; when the numbers cannot be a date in that order
 * (the first cannot be a month in a month-first locale, the second cannot be one in a day-first
 * locale) the order flips.
 */
private fun slashDate(first: Int, second: Int, yearWord: String, dayFirst: Boolean, today: LocalDate): LocalDate? {
    val year = yearWord.toIntOrNull()?.let { if (yearWord.length == 2) 2000 + it else it }
    val inLocaleOrder = if (dayFirst) datedFrom(second, first, year, today) else datedFrom(first, second, year, today)
    if (inLocaleOrder != null) return inLocaleOrder
    return if (dayFirst) datedFrom(first, second, year, today) else datedFrom(second, first, year, today)
}

private fun datedFrom(month: Int, day: Int, year: Int?, today: LocalDate): LocalDate? {
    if (month !in 1..12 || day !in 1..31) return null
    if (year != null) return runCatching { LocalDate(year, month, day) }.getOrNull()
    val date = runCatching { LocalDate(today.year, month, day) }.getOrNull() ?: return null
    // Forward date: one that has passed this year is next year's. Today stays today.
    return if (date < today) runCatching { LocalDate(today.year + 1, month, day) }.getOrNull() else date
}

private fun timeOf(hourValue: Int, minute: Int, meridiem: String): LocalTime? {
    if (minute !in 0..59) return null
    val suffix = meridiem.lowercase()
    val hour = when {
        suffix.isEmpty() -> hourValue.takeIf { it in 0..23 }
        hourValue !in 1..12 -> null
        suffix == "pm" -> if (hourValue == 12) 12 else hourValue + 12
        else -> if (hourValue == 12) 0 else hourValue
    } ?: return null
    return LocalTime(hour, minute)
}

// --- Patterns ------------------------------------------------------------------------------------

// A date word starts the text or follows whitespace, and ends at whitespace, the end, or the
// punctuation that can follow a word ("tomorrow!", "3pm.").
private val WB = """(?:^|(?<=\s))"""
private val WE = """(?=\s|$|[!?.,;)])"""

private const val MONTH_NAMES =
    "jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?"

private const val WEEKDAY_NAMES =
    "monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tues?|wed|thu(?:rs?)?|fri|sat|sun"

private val MONTH_BY_PREFIX = mapOf(
    "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
    "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
)

private val WEEKDAY_BY_PREFIX = mapOf(
    "mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "wed" to DayOfWeek.WEDNESDAY,
    "thu" to DayOfWeek.THURSDAY, "fri" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY,
    "sun" to DayOfWeek.SUNDAY,
)

private val FULL_WEEKDAY_NAMES =
    setOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")

private val IC = RegexOption.IGNORE_CASE

private val RELATIVE_TIME_RE = Regex("""${WB}in\s+(\d+|an?)\s+(hours?|hrs?|minutes?|mins?)$WE""", IC)
private val RELATIVE_DATE_RE = Regex("""${WB}in\s+(\d+|an?)\s+(days?|weeks?|months?|years?)$WE""", IC)
private val TODAY_TOMORROW_RE = Regex("""${WB}(today|tomorrow)$WE""", IC)
private val NEXT_PERIOD_RE = Regex("""${WB}next\s+(week|month)$WE""", IC)
private val WEEKDAY_RE = Regex("""${WB}(?:(this|next)\s+)?($WEEKDAY_NAMES)$WE""", IC)
private val MONTH_DAY_RE =
    Regex("""${WB}($MONTH_NAMES)\.?\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+(\d{4}))?$WE""", IC)
private val DAY_MONTH_RE =
    Regex("""${WB}(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?($MONTH_NAMES)\.?(?:,?\s+(\d{4}))?$WE""", IC)
private val ISO_RE = Regex("""${WB}(\d{4})-(\d{1,2})-(\d{1,2})$WE""")
private val SLASH_RE = Regex("""${WB}(\d{1,2})/(\d{1,2})(?:/(\d{4}|\d{2}))?$WE""")
private val CLOCK_RE = Regex("""${WB}(?:at\s+)?(\d{1,2}):(\d{2})(?:\s*(am|pm))?$WE""", IC)
private val HOUR_RE = Regex("""${WB}(?:at\s+)?(\d{1,2})\s*(am|pm)$WE""", IC)

/** `on`/`by`/`due` right before a phrase, whatever it starts with. */
private val CONNECTOR_BEFORE_RE = Regex("""(?:^|\s)((?:(?:on|by|due)\s+)+)$""", IC)

private val ABBREVIATION_PREFIX_RE = Regex("""(?:^|\s)(?:on|next|this|by|due)\s+$""", IC)
private val TIME_AFTER_RE =
    Regex("""^\s*(?:at\s+)?(?:\d{1,2}:\d{2}|\d{1,2}(?::\d{2})?\s*[ap]m\b)""", IC)

private val GAP_DATE_THEN_TIME_RE = Regex("""[\s,]*""")
private val GAP_TIME_THEN_DATE_RE = Regex("""[\s,]*(?:on[\s,]+)?""", IC)
