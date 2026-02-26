package com.rendyhd.vicu.util.parser

data class PriorityResult(
    val priority: Int?,
    val tokens: List<ParsedToken>,
)

private val WORD_PRIORITIES = mapOf(
    "urgent" to 4,
    "high" to 3,
    "medium" to 2,
    "low" to 1,
)

// Todoist inverts: p1 = highest urgency → Vikunja 4
private val TODOIST_MAP = mapOf(1 to 4, 2 to 3, 3 to 2, 4 to 1)

fun extractPriority(
    input: String,
    mode: SyntaxMode,
    consumed: MutableList<IntRange>,
): PriorityResult {
    val tokens = mutableListOf<ParsedToken>()

    // Word-based priority: !urgent, !high, !medium, !low (both modes)
    val wordRe = Regex("""(?:^|(?<=\s))!(urgent|high|medium|low)(?=\s|$)""", RegexOption.IGNORE_CASE)
    for (match in wordRe.findAll(input)) {
        val start = match.range.first
        val end = match.range.last + 1
        if (consumed.any { start < it.last + 1 && end > it.first }) continue
        val word = match.groupValues[1].lowercase()
        val priority = WORD_PRIORITIES[word] ?: continue
        consumed.add(start until end)
        tokens.add(
            ParsedToken(
                type = TokenType.PRIORITY,
                start = start,
                end = end,
                value = priority,
                raw = match.value,
            ),
        )
        return PriorityResult(priority, tokens)
    }

    if (mode == SyntaxMode.TODOIST) {
        // Todoist: p1-p4 with word boundary
        val re = Regex("""(?:^|(?<=\s))p([1-4])(?=\s|$)""")
        for (match in re.findAll(input)) {
            val start = match.range.first
            val end = match.range.last + 1
            if (consumed.any { start < it.last + 1 && end > it.first }) continue
            val num = match.groupValues[1].toInt()
            val priority = TODOIST_MAP[num] ?: continue
            consumed.add(start until end)
            tokens.add(
                ParsedToken(
                    type = TokenType.PRIORITY,
                    start = start,
                    end = end,
                    value = priority,
                    raw = match.value,
                ),
            )
            return PriorityResult(priority, tokens)
        }
    } else {
        // Vikunja: !1-!4 with word boundary
        val re = Regex("""(?:^|(?<=\s))!([1-4])(?=\s|$)""")
        for (match in re.findAll(input)) {
            val start = match.range.first
            val end = match.range.last + 1
            if (consumed.any { start < it.last + 1 && end > it.first }) continue
            val priority = match.groupValues[1].toInt()
            consumed.add(start until end)
            tokens.add(
                ParsedToken(
                    type = TokenType.PRIORITY,
                    start = start,
                    end = end,
                    value = priority,
                    raw = match.value,
                ),
            )
            return PriorityResult(priority, tokens)
        }
    }

    return PriorityResult(null, tokens)
}
