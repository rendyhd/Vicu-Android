package com.rendyhd.vicu.util.parser

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

data class DateResult(
    val dueDate: LocalDateTime?,
    val tokens: List<ParsedToken>,
)

data class BangTodayResult(
    val title: String,
    val dueDate: LocalDateTime?,
)

/**
 * Extract a date from input text using custom regex patterns.
 * Handles: today, tomorrow, tomorrow 3pm, day-of-week, next week,
 * in N days/weeks, and "jan 15" style dates.
 */
fun extractDate(
    input: String,
    consumed: MutableList<IntRange>,
): DateResult {
    val tokens = mutableListOf<ParsedToken>()

    // Build working text with consumed regions replaced by spaces
    val working = buildWorkingText(input, consumed)

    for (matcher in DATE_MATCHERS) {
        val match = matcher.pattern.find(working) ?: continue
        val start = match.range.first
        val end = match.range.last + 1
        // Verify no overlap
        if (consumed.any { start < it.last + 1 && end > it.first }) continue
        val date = matcher.resolve(match) ?: continue
        consumed.add(start until end)
        tokens.add(
            ParsedToken(
                type = TokenType.DATE,
                start = start,
                end = end,
                value = date,
                raw = input.substring(start, end),
            ),
        )
        return DateResult(date, tokens)
    }

    return DateResult(null, tokens)
}

/**
 * Extract the `!` → today shortcut. Independent of the NLP parser —
 * always runs (even when parser is disabled).
 */
fun extractBangToday(input: String): BangTodayResult {
    val trimmed = input.trim()

    // Standalone `!`
    if (trimmed == "!") {
        return BangTodayResult("", LocalDate.now().atStartOfDay())
    }

    // Trailing `!` at end of string
    val trailingRe = Regex("""^(.+?)\s*!$""")
    val trailingMatch = trailingRe.find(trimmed)
    if (trailingMatch != null) {
        return BangTodayResult(
            trailingMatch.groupValues[1].trim(),
            LocalDate.now().atStartOfDay(),
        )
    }

    // Leading `!` at start, but NOT if followed by priority token
    val leadingRe = Regex("""^!\s*(.+)$""")
    val leadingMatch = leadingRe.find(trimmed)
    if (leadingMatch != null) {
        val rest = leadingMatch.groupValues[1]
        val isPriorityToken = Regex("""^[1-4](?:\s|$)""").containsMatchIn(rest) ||
            Regex("""^(?:urgent|high|medium|low)(?:\s|$)""", RegexOption.IGNORE_CASE).containsMatchIn(rest)
        if (!isPriorityToken) {
            return BangTodayResult(
                rest.trim(),
                LocalDate.now().atStartOfDay(),
            )
        }
    }

    return BangTodayResult(input, null)
}

private fun buildWorkingText(input: String, consumed: List<IntRange>): String {
    val chars = input.toCharArray()
    for (c in consumed) {
        for (i in c) {
            if (i < chars.size) chars[i] = ' '
        }
    }
    return String(chars)
}

// --- Date matcher infrastructure ---

private data class DateMatcher(
    val pattern: Regex,
    val resolve: (MatchResult) -> LocalDateTime?,
)

private val MONTH_MAP = mapOf(
    "jan" to 1, "january" to 1,
    "feb" to 2, "february" to 2,
    "mar" to 3, "march" to 3,
    "apr" to 4, "april" to 4,
    "may" to 5,
    "jun" to 6, "june" to 6,
    "jul" to 7, "july" to 7,
    "aug" to 8, "august" to 8,
    "sep" to 9, "september" to 9,
    "oct" to 10, "october" to 10,
    "nov" to 11, "november" to 11,
    "dec" to 12, "december" to 12,
)

private val DAY_MAP = mapOf(
    "monday" to DayOfWeek.MONDAY,
    "tuesday" to DayOfWeek.TUESDAY,
    "wednesday" to DayOfWeek.WEDNESDAY,
    "thursday" to DayOfWeek.THURSDAY,
    "friday" to DayOfWeek.FRIDAY,
    "saturday" to DayOfWeek.SATURDAY,
    "sunday" to DayOfWeek.SUNDAY,
    "mon" to DayOfWeek.MONDAY,
    "tue" to DayOfWeek.TUESDAY,
    "wed" to DayOfWeek.WEDNESDAY,
    "thu" to DayOfWeek.THURSDAY,
    "fri" to DayOfWeek.FRIDAY,
    "sat" to DayOfWeek.SATURDAY,
    "sun" to DayOfWeek.SUNDAY,
)

private fun parseTime(hourStr: String?, ampm: String?): LocalTime {
    if (hourStr == null) return LocalTime.NOON
    var hour = hourStr.toIntOrNull() ?: return LocalTime.NOON
    val suffix = ampm?.lowercase() ?: ""
    if (suffix == "pm" && hour < 12) hour += 12
    if (suffix == "am" && hour == 12) hour = 0
    return LocalTime.of(hour.coerceIn(0, 23), 0)
}

private val WB = """(?:^|(?<=\s))"""
private val WE = """(?=\s|$)"""

private val DATE_MATCHERS = listOf(
    // "tomorrow 3pm" / "tomorrow at 3pm" / "tomorrow at 3 pm"
    DateMatcher(
        Regex("""${WB}tomorrow\s+(?:at\s+)?(\d{1,2})\s*(am|pm)$WE""", RegexOption.IGNORE_CASE),
    ) { match ->
        val time = parseTime(match.groupValues[1], match.groupValues[2])
        LocalDate.now().plusDays(1).atTime(time)
    },
    // "today 3pm" / "today at 3pm"
    DateMatcher(
        Regex("""${WB}today\s+(?:at\s+)?(\d{1,2})\s*(am|pm)$WE""", RegexOption.IGNORE_CASE),
    ) { match ->
        val time = parseTime(match.groupValues[1], match.groupValues[2])
        LocalDate.now().atTime(time)
    },
    // "today"
    DateMatcher(
        Regex("""${WB}today$WE""", RegexOption.IGNORE_CASE),
    ) { LocalDate.now().atTime(23, 59, 59) },
    // "tomorrow"
    DateMatcher(
        Regex("""${WB}tomorrow$WE""", RegexOption.IGNORE_CASE),
    ) { LocalDate.now().plusDays(1).atTime(12, 0) },
    // "next week"
    DateMatcher(
        Regex("""${WB}next\s+week$WE""", RegexOption.IGNORE_CASE),
    ) {
        LocalDate.now()
            .with(TemporalAdjusters.next(DayOfWeek.MONDAY))
            .atTime(9, 0)
    },
    // "in N days/weeks/months/years"
    DateMatcher(
        Regex("""${WB}in\s+(\d+)\s+(days?|weeks?|months?|years?)$WE""", RegexOption.IGNORE_CASE),
    ) { match ->
        val n = match.groupValues[1].toLongOrNull() ?: return@DateMatcher null
        val unit = match.groupValues[2].lowercase().trimEnd('s')
        val date = when (unit) {
            "day" -> LocalDate.now().plusDays(n)
            "week" -> LocalDate.now().plusWeeks(n)
            "month" -> LocalDate.now().plusMonths(n)
            "year" -> LocalDate.now().plusYears(n)
            else -> return@DateMatcher null
        }
        date.atTime(12, 0)
    },
    // Day of week: "monday", "tuesday", etc. (next occurrence)
    DateMatcher(
        Regex(
            """${WB}(monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|wed|thu|fri|sat|sun)$WE""",
            RegexOption.IGNORE_CASE,
        ),
    ) { match ->
        val dow = DAY_MAP[match.groupValues[1].lowercase()] ?: return@DateMatcher null
        LocalDate.now().with(TemporalAdjusters.next(dow)).atTime(12, 0)
    },
    // "jan 15" / "january 15" / "jan 15th"
    DateMatcher(
        Regex(
            """${WB}(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\s+(\d{1,2})(?:st|nd|rd|th)?$WE""",
            RegexOption.IGNORE_CASE,
        ),
    ) { match ->
        val month = MONTH_MAP[match.groupValues[1].lowercase()] ?: return@DateMatcher null
        val day = match.groupValues[2].toIntOrNull() ?: return@DateMatcher null
        if (day < 1 || day > 31) return@DateMatcher null
        var date = try {
            LocalDate.of(LocalDate.now().year, month, day)
        } catch (_: Exception) {
            return@DateMatcher null
        }
        // Forward date: if in the past, use next year
        if (date.isBefore(LocalDate.now())) {
            date = date.plusYears(1)
        }
        date.atTime(12, 0)
    },
    // "15 jan" / "15th january"
    DateMatcher(
        Regex(
            """${WB}(\d{1,2})(?:st|nd|rd|th)?\s+(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)$WE""",
            RegexOption.IGNORE_CASE,
        ),
    ) { match ->
        val day = match.groupValues[1].toIntOrNull() ?: return@DateMatcher null
        val month = MONTH_MAP[match.groupValues[2].lowercase()] ?: return@DateMatcher null
        if (day < 1 || day > 31) return@DateMatcher null
        var date = try {
            LocalDate.of(LocalDate.now().year, month, day)
        } catch (_: Exception) {
            return@DateMatcher null
        }
        if (date.isBefore(LocalDate.now())) {
            date = date.plusYears(1)
        }
        date.atTime(12, 0)
    },
)
