package com.rendyhd.vicu.util.parser

import com.rendyhd.vicu.location.PlaceMatch
import kotlinx.datetime.LocalDateTime

enum class SyntaxMode { TODOIST, VIKUNJA }

/**
 * [PLACE] is the phone's own: "when I get home" for a saved place (PlacePhrases), read only when
 * the caller passes the saved places. It is not in the shared contract (the desktop saves no places).
 * [LENGTH] is `for 30m` when it makes a time block (section 5.4 of the contract).
 *
 * [PERSON], [START], [ESTIMATE], [FLAG] and [REQUEST] are Pro's phrases (section 5.5), read only
 * while [ParserConfig.pro] is on.
 */
enum class TokenType { LABEL, PROJECT, PRIORITY, RECURRENCE, DATE, LENGTH, PLACE, PERSON, START, ESTIMATE, FLAG, REQUEST }

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

/** The person a leading `Jan:` names (their promise to you) or `for Jan:` (yours to them). */
data class ParsedPerson(val id: Long, val mine: Boolean)

enum class RecurrenceUnit { DAY, WEEK, MONTH, YEAR }

/** The time block `for 30m` makes with the text's time of day, in the zone it was read in. */
data class ParsedBlock(val start: LocalDateTime, val end: LocalDateTime)

data class ParseResult(
    val title: String,
    val dueDate: LocalDateTime? = null,
    /**
     * Whether the text named a time of day. When false, [dueDate] is only a date and is stored
     * date-only (see DueDates.fromParsed); its time of day is a placeholder.
     */
    val dueDateHasTime: Boolean = false,
    /** With a block the due date is that day, date-only, and the block holds the time. */
    val block: ParsedBlock? = null,
    val priority: Int? = null,
    val labels: List<String> = emptyList(),
    val project: String? = null,
    val recurrence: ParsedRecurrence? = null,
    /** A saved place the text names with "when I get home", for a location reminder. */
    val place: PlaceMatch? = null,
    // Pro (section 5.5): always present, set only while [ParserConfig.pro] is on.
    /** `Jan:` at the start: that person's promise to you; `for Jan:`: yours to them. */
    val person: ParsedPerson? = null,
    /** `starts monday`: a start date (local midnight without a time). Null when a block holds the start. */
    val start: LocalDateTime? = null,
    /** `~1h30`: minutes. */
    val estimate: Int? = null,
    /** `!flag` */
    val flag: Boolean = false,
    /** `req:` at the start: a request to triage. */
    val request: Boolean = false,
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
    /** Pro's phrases (section 5.5). Off unless true; with it off the text is read as before. */
    val pro: Boolean = false,
)

private val SYNTAX_PREFIXES = mapOf(
    SyntaxMode.TODOIST to SyntaxPrefixes(label = "@", project = "#"),
    SyntaxMode.VIKUNJA to SyntaxPrefixes(label = "*", project = "+"),
)

fun getPrefixes(mode: SyntaxMode): SyntaxPrefixes =
    SYNTAX_PREFIXES[mode] ?: SyntaxPrefixes("@", "#")
