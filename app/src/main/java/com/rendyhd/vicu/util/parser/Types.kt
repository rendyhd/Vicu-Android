package com.rendyhd.vicu.util.parser

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
    val dueDate: java.time.LocalDateTime? = null,
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
)

private val SYNTAX_PREFIXES = mapOf(
    SyntaxMode.TODOIST to SyntaxPrefixes(label = "@", project = "#"),
    SyntaxMode.VIKUNJA to SyntaxPrefixes(label = "*", project = "+"),
)

fun getPrefixes(mode: SyntaxMode): SyntaxPrefixes =
    SYNTAX_PREFIXES[mode] ?: SyntaxPrefixes("@", "#")
