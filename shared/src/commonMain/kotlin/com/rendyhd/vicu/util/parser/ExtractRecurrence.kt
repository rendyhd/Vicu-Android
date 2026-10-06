package com.rendyhd.vicu.util.parser

/**
 * [weekday] is the region of the weekday word of "every monday". The recurrence token covers only
 * "every" while the weekday is still available as the due date: the caller either leaves it to the
 * date extractor (no other date in the input) or claims it for the recurrence (another date is
 * present).
 */
data class RecurrenceResult(
    val recurrence: ParsedRecurrence?,
    val tokens: List<ParsedToken>,
    val weekday: IntRange? = null,
)

private val SHORTHAND = mapOf(
    "daily" to ParsedRecurrence(1, RecurrenceUnit.DAY),
    "weekly" to ParsedRecurrence(1, RecurrenceUnit.WEEK),
    "monthly" to ParsedRecurrence(1, RecurrenceUnit.MONTH),
    "yearly" to ParsedRecurrence(1, RecurrenceUnit.YEAR),
    "annually" to ParsedRecurrence(1, RecurrenceUnit.YEAR),
    "biweekly" to ParsedRecurrence(2, RecurrenceUnit.WEEK),
    "fortnightly" to ParsedRecurrence(2, RecurrenceUnit.WEEK),
)

private val UNIT_MAP = mapOf(
    "day" to RecurrenceUnit.DAY,
    "days" to RecurrenceUnit.DAY,
    "week" to RecurrenceUnit.WEEK,
    "weeks" to RecurrenceUnit.WEEK,
    "month" to RecurrenceUnit.MONTH,
    "months" to RecurrenceUnit.MONTH,
    "year" to RecurrenceUnit.YEAR,
    "years" to RecurrenceUnit.YEAR,
)

/**
 * Extract recurrence from [input] (docs/cross-app-semantics-v1.md section 5.2). Patterns:
 * - "every N unit", "every unit";
 * - "every <weekday>" (full name): weekly; the weekday is returned as [RecurrenceResult.weekday]
 *   so the caller can use it as the due date;
 * - shorthand "daily", "weekly", "monthly", "yearly", "annually", "biweekly", "fortnightly",
 *   only when it is the last word of what is left ("Water plants daily"), so a word that
 *   describes the rest ("weekly standup", "Daily review tomorrow") is left alone.
 */
fun extractRecurrence(
    input: String,
    consumed: MutableList<IntRange>,
): RecurrenceResult {
    val tokens = mutableListOf<ParsedToken>()

    // "every N unit" or "every unit"
    val everyRe = Regex(
        """(?:^|(?<=\s))every\s+(\d+\s+)?(days?|weeks?|months?|years?)(?=\s|$)""",
        RegexOption.IGNORE_CASE,
    )
    for (match in everyRe.findAll(input)) {
        val start = match.range.first
        val end = match.range.last + 1
        if (consumed.any { start < it.last + 1 && end > it.first }) continue
        val count = match.groupValues[1].trim()
        val interval = if (count.isEmpty()) 1 else count.toIntOrNull() ?: continue
        if (interval < 1) continue
        val unit = UNIT_MAP[match.groupValues[2].lowercase()] ?: continue
        val recurrence = ParsedRecurrence(interval, unit)
        consumed.add(start until end)
        tokens.add(
            ParsedToken(
                type = TokenType.RECURRENCE,
                start = start,
                end = end,
                value = recurrence,
                raw = match.value,
            ),
        )
        return RecurrenceResult(recurrence, tokens)
    }

    // "every monday": weekly. Only the word "every" is consumed here.
    val weekdayRe = Regex(
        """(?:^|(?<=\s))every(\s+)(monday|tuesday|wednesday|thursday|friday|saturday|sunday)(?=\s|$)""",
        RegexOption.IGNORE_CASE,
    )
    for (match in weekdayRe.findAll(input)) {
        val start = match.range.first
        val everyEnd = start + "every".length
        val weekdayStart = everyEnd + match.groupValues[1].length
        val weekdayEnd = weekdayStart + match.groupValues[2].length
        if (consumed.any { start < it.last + 1 && weekdayEnd > it.first }) continue
        val recurrence = ParsedRecurrence(1, RecurrenceUnit.WEEK)
        consumed.add(start until everyEnd)
        tokens.add(
            ParsedToken(
                type = TokenType.RECURRENCE,
                start = start,
                end = everyEnd,
                value = recurrence,
                raw = input.substring(start, everyEnd),
            ),
        )
        return RecurrenceResult(recurrence, tokens, weekday = weekdayStart until weekdayEnd)
    }

    // Shorthand: "daily", "weekly", etc. Only the last word of what is left counts.
    val shorthandRe = Regex(
        """(?:^|(?<=\s))(daily|weekly|monthly|yearly|annually|biweekly|fortnightly)(?=\s|!|$)""",
        RegexOption.IGNORE_CASE,
    )
    for (match in shorthandRe.findAll(input)) {
        val start = match.range.first
        val end = match.range.last + 1
        if (consumed.any { start < it.last + 1 && end > it.first }) continue
        if (!isLastWord(input, end, consumed)) continue
        val recurrence = SHORTHAND[match.groupValues[1].lowercase()]?.copy() ?: continue
        consumed.add(start until end)
        tokens.add(
            ParsedToken(
                type = TokenType.RECURRENCE,
                start = start,
                end = end,
                value = recurrence,
                raw = match.value,
            ),
        )
        return RecurrenceResult(recurrence, tokens)
    }

    return RecurrenceResult(null, tokens)
}

/**
 * True when nothing but consumed text, whitespace and a lone `!` (the today shortcut) follows
 * [end]: the word before it is the last word of the input as it is left after labels, projects and
 * priority were taken out.
 */
private fun isLastWord(input: String, end: Int, consumed: List<IntRange>): Boolean {
    val rest = StringBuilder()
    for (i in end until input.length) {
        if (consumed.none { i in it }) rest.append(input[i])
    }
    return Regex("""^\s*!?\s*$""").matches(rest)
}
