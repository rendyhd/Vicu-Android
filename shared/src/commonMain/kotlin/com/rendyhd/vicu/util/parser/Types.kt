package com.rendyhd.vicu.util.parser

import kotlinx.datetime.LocalDateTime

enum class SyntaxMode { TODOIST, VIKUNJA }

enum class TokenType { LABEL, PROJECT, PRIORITY, RECURRENCE, DATE }

data class ParsedToken(
    val type: TokenType,
    val start: Int,
    val end: Int,
    val value: Any?,
    val raw: String,
)

data class ParsedRecurrence(
    val interval: Int,
    val unit: RecurrenceUnit,
)

enum class RecurrenceUnit { DAY, WEEK, MONTH, YEAR }

data class ParseResult(
    val title: String,
    val dueDate: LocalDateTime? = null,
    /**
     * Whether the text named a time of day. When false, [dueDate] is only a date and is stored
     * date-only (see DueDates.fromParsed); its time of day is a placeholder.
     */
    val dueDateHasTime: Boolean = false,
    val priority: Int? = null,
    val labels: List<String> = emptyList(),
    val project: String? = null,
    val recurrence: ParsedRecurrence? = null,
    val tokens: List<ParsedToken> = emptyList(),
)

data class SyntaxPrefixes(
    val label: String,
    val project: String,
)

data class ParserConfig(
    val enabled: Boolean = true,
    val syntaxMode: SyntaxMode = SyntaxMode.TODOIST,
    val suppressTypes: Set<TokenType> = emptySet(),
    val bangToday: Boolean = true,
    /**
     * BCP 47 tag deciding the order of slash dates ("5/11": month/day in en-US, day/month in
     * en-GB). Null means the device locale.
     */
    val locale: String? = null,
)

private val SYNTAX_PREFIXES = mapOf(
    SyntaxMode.TODOIST to SyntaxPrefixes(label = "@", project = "#"),
    SyntaxMode.VIKUNJA to SyntaxPrefixes(label = "*", project = "+"),
)

fun getPrefixes(mode: SyntaxMode): SyntaxPrefixes =
    SYNTAX_PREFIXES[mode] ?: SyntaxPrefixes("@", "#")
