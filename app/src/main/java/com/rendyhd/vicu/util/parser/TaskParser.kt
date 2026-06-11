package com.rendyhd.vicu.util.parser

object TaskParser {

    /**
     * Parse free-form task input into structured fields.
     *
     * Extraction order: Labels → Projects → Priority → Recurrence → Dates → Title
     *
     * The trailing `!` → today shortcut is controlled by `config.bangToday`.
     * When the parser is disabled, the caller should handle it via `extractBangToday()` directly.
     */
    fun parse(
        rawInput: String,
        config: ParserConfig = ParserConfig(),
    ): ParseResult {
        val result = MutableParseResult(title = rawInput)

        if (rawInput.isBlank()) return result.toParseResult()

        if (!config.enabled) return result.toParseResult()

        val prefixes = getPrefixes(config.syntaxMode)
        val consumed = mutableListOf<IntRange>()
        val suppress = config.suppressTypes

        // 1. Labels
        if (TokenType.LABEL !in suppress) {
            val (labels, tokens) = extractLabels(rawInput, prefixes.label, consumed)
            result.labels = labels
            result.tokens.addAll(tokens)
        }

        // 2. Project
        if (TokenType.PROJECT !in suppress) {
            val (project, tokens) = extractProject(rawInput, prefixes.project, consumed)
            result.project = project
            result.tokens.addAll(tokens)
        }

        // 3. Priority
        if (TokenType.PRIORITY !in suppress) {
            val (priority, tokens) = extractPriority(rawInput, config.syntaxMode, consumed)
            result.priority = priority
            result.tokens.addAll(tokens)
        }

        // 4. Recurrence
        if (TokenType.RECURRENCE !in suppress) {
            val (recurrence, tokens) = extractRecurrence(rawInput, consumed)
            result.recurrence = recurrence
            result.tokens.addAll(tokens)
        }

        // 5. Dates
        if (TokenType.DATE !in suppress) {
            val (dueDate, tokens) = extractDate(rawInput, consumed)
            result.dueDate = dueDate
            result.tokens.addAll(tokens)
        }

        // 6. Build title from non-consumed regions
        result.title = buildTitle(rawInput, consumed)

        // 7. Leading/trailing ! → today (when enabled, no date found, and DATE not suppressed —
        // the suppression gate makes dismissing the Today chip stick for bang-created dates)
        if (config.bangToday && result.dueDate == null && TokenType.DATE !in suppress) {
            val bang = extractBangToday(result.title)
            if (bang.dueDate != null) {
                result.title = bang.title
                result.dueDate = bang.dueDate
                // The bang was found in the rebuilt title; map it back to the raw input as
                // the first (leading) or last (trailing/standalone) non-consumed,
                // non-whitespace character so the field highlights it like other tokens.
                val bangIndex = when (bang.form) {
                    BangForm.LEADING -> rawInput.indices.firstOrNull { i ->
                        !rawInput[i].isWhitespace() && consumed.none { r -> i in r }
                    }
                    BangForm.TRAILING, BangForm.STANDALONE -> rawInput.indices.lastOrNull { i ->
                        !rawInput[i].isWhitespace() && consumed.none { r -> i in r }
                    }
                    BangForm.NONE -> null // unreachable: guarded by bang.dueDate != null above
                }
                if (bangIndex != null && rawInput[bangIndex] == '!') {
                    result.tokens.add(
                        ParsedToken(
                            type = TokenType.DATE,
                            start = bangIndex,
                            end = bangIndex + 1,
                            value = bang.dueDate,
                            raw = "!",
                        ),
                    )
                }
            }
        }

        return result.toParseResult()
    }
}

private class MutableParseResult(
    var title: String = "",
    var dueDate: java.time.LocalDateTime? = null,
    var priority: Int? = null,
    var labels: List<String> = emptyList(),
    var project: String? = null,
    var recurrence: ParsedRecurrence? = null,
    val tokens: MutableList<ParsedToken> = mutableListOf(),
) {
    fun toParseResult() = ParseResult(
        title = title,
        dueDate = dueDate,
        priority = priority,
        labels = labels,
        project = project,
        recurrence = recurrence,
        tokens = tokens.toList(),
    )
}

/**
 * Build the final title by removing all consumed regions and collapsing whitespace.
 */
private fun buildTitle(input: String, consumed: List<IntRange>): String {
    val sorted = consumed.sortedBy { it.first }
    val sb = StringBuilder()
    var pos = 0
    for (region in sorted) {
        if (region.first > pos) {
            sb.append(input, pos, region.first)
        }
        pos = region.last + 1
    }
    if (pos < input.length) {
        sb.append(input, pos, input.length)
    }
    return sb.toString().replace(Regex("""\s+"""), " ").trim()
}
