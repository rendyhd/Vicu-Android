package com.rendyhd.vicu.util.parser

data class RecurrenceResult(
    val recurrence: ParsedRecurrence?,
    val tokens: List<ParsedToken>,
)

private val SHORTHAND = mapOf(
    "daily" to ParsedRecurrence(1, RecurrenceUnit.DAY),
    "weekly" to ParsedRecurrence(1, RecurrenceUnit.WEEK),
    "monthly" to ParsedRecurrence(1, RecurrenceUnit.MONTH),
    "yearly" to ParsedRecurrence(1, RecurrenceUnit.YEAR),
    "annually" to ParsedRecurrence(1, RecurrenceUnit.YEAR),
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
        val interval = match.groupValues[1].trim().let { if (it.isEmpty()) 1 else it.toInt() }
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

    // Shorthand: "daily", "weekly", etc. — only if standalone
    val shorthandRe = Regex(
        """(?:^|(?<=\s))(daily|weekly|monthly|yearly|annually)(?=\s|$)""",
        RegexOption.IGNORE_CASE,
    )
    for (match in shorthandRe.findAll(input)) {
        val start = match.range.first
        val end = match.range.last + 1
        if (consumed.any { start < it.last + 1 && end > it.first }) continue
        if (!isStandalone(input, start, end, consumed)) continue
        val word = match.groupValues[1].lowercase()
        val recurrence = SHORTHAND[word]?.copy() ?: continue
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
 * Check if the word at [start, end] is standalone — meaning the remaining
 * non-consumed text around it doesn't form a phrase.
 * Returns true only if all surrounding non-consumed text is whitespace.
 */
private fun isStandalone(
    input: String,
    start: Int,
    end: Int,
    consumed: List<IntRange>,
): Boolean {
    val isConsumed = BooleanArray(input.length)
    for (c in consumed) {
        for (i in c) {
            if (i < isConsumed.size) isConsumed[i] = true
        }
    }
    // Also mark our candidate as consumed
    for (i in start until end) {
        if (i < isConsumed.size) isConsumed[i] = true
    }
    // Check if there's any non-whitespace, non-consumed character
    for (i in input.indices) {
        if (!isConsumed[i] && !input[i].isWhitespace()) {
            return false
        }
    }
    return true
}
