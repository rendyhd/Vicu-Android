package com.rendyhd.vicu.util.parser

import com.rendyhd.vicu.location.PlaceMatch
import com.rendyhd.vicu.location.PlacePhrases
import com.rendyhd.vicu.location.SavedPlace
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.util.TimeBlocks
import com.rendyhd.vicu.util.deviceLocaleTag
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

object TaskParser {

    /**
     * Parse free-form task input into structured fields. Implements shared-parser-spec.md and
     * section 5 of docs/cross-app-semantics-v1.md; `test-fixtures/nlp-corpus-v1.json` is the test.
     *
     * Extraction order: Place -> Labels -> Projects -> Priority -> Recurrence -> Dates -> Length ->
     * Title -> `!` (`for` phrases are found before Recurrence, so Dates does not read them; section 5.4)
     *
     * The `!` -> today shortcut (a standalone, leading or trailing `!`) is controlled by
     * `config.bangToday` and has one rule, [extractBangToday]: this function applies it whether the
     * parser is enabled or not. An explicit date wins over it, but the `!` is still removed from
     * the title ("Call mom tomorrow!").
     *
     * @param reference "Now" in [zone] for relative phrases; the device clock unless a test passes one.
     * @param zone The zone [reference] is in; "in 2 hours" counts real time in it.
     * @param places The saved places "when I get home" may name ([TokenType.PLACE]); none outside
     *   the phone's quick add, so the shared contract is unchanged.
     */
    fun parse(
        rawInput: String,
        config: ParserConfig = ParserConfig(),
        reference: LocalDateTime? = null,
        zone: TimeZone = TimeZone.currentSystemDefault(),
        places: List<SavedPlace> = emptyList(),
    ): ParseResult {
        val now = reference ?: systemNow(zone)
        val result = MutableParseResult(title = rawInput)

        if (rawInput.isBlank()) return result.toParseResult()

        val suppress = config.suppressTypes

        if (!config.enabled) {
            // Everything stays in the title; only the `!` shortcut still works.
            if (config.bangToday && TokenType.DATE !in suppress) {
                val bang = extractBangToday(rawInput, now)
                if (bang.dueDate != null) {
                    result.title = bang.title
                    result.dueDate = bang.dueDate
                    result.dueDateHasTime = false
                }
            }
            return result.toParseResult()
        }

        val prefixes = getPrefixes(config.syntaxMode)
        val consumed = mutableListOf<IntRange>()

        // 0. A saved place ("when I get home"), first, so no word of the phrase is read as a date.
        if (TokenType.PLACE !in suppress) {
            val match = PlacePhrases.match(rawInput, places)
            if (match != null) {
                consumed.add(match.consumed)
                result.place = match
                result.tokens.add(
                    ParsedToken(
                        type = TokenType.PLACE,
                        start = match.start,
                        end = match.end,
                        value = match,
                        raw = rawInput.substring(match.start, match.end),
                    ),
                )
            }
        }

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

        // `for 30m` and `for 3 months` do not split a date. A suppressed length still does not: its
        // words simply stay in the title and the time stays the due time.
        val forPhrases = findForPhrases(rawInput, consumed)

        // 4. Recurrence
        var weekday: IntRange? = null
        if (TokenType.RECURRENCE !in suppress) {
            val extracted = extractRecurrence(rawInput, consumed)
            result.recurrence = extracted.recurrence
            result.tokens.addAll(extracted.tokens)
            weekday = extracted.weekday
        }

        // 5. Dates
        if (TokenType.DATE !in suppress) {
            val options = DateOptions(
                reference = now,
                zone = zone,
                dayFirst = isDayFirstLocale(config.locale ?: deviceLocaleTag()),
                hidden = forPhrases.hidden,
            )
            var found: DateResult? = null
            if (weekday != null) {
                // "every monday": the weekday is the due date unless the input has another date.
                // A time on its own ("every monday 10am") is not another date; it goes with the
                // weekday.
                val trial = (consumed + listOf(weekday)).toMutableList()
                val other = extractDate(rawInput, trial, options.copy(skipTimeOnly = true))
                if (other.dueDate != null) {
                    consumed.clear()
                    consumed.addAll(trial)
                    found = other
                    result.claimWeekdayForRecurrence(rawInput, weekday)
                }
            }
            if (found == null) found = extractDate(rawInput, consumed, options.copy(prefer = weekday))
            result.dueDate = found.dueDate
            result.dueDateHasTime = found.hasTime
            result.tokens.addAll(found.tokens)
        } else if (weekday != null) {
            // The date was dismissed: "every monday" is only the recurrence.
            consumed.add(weekday)
            result.claimWeekdayForRecurrence(rawInput, weekday)
        }

        // 6. Length: with a time of day it is a block, and the block holds the time. Otherwise the
        // words stay in the title.
        val length = forPhrases.length
        val due = result.dueDate
        if (length != null && TokenType.LENGTH !in suppress && due != null && result.dueDateHasTime) {
            val start = LocalDateTime(due.date, LocalTime(due.hour, due.minute))
            val startAt = start.toInstant(zone)
            val endAt = startAt + length.minutes.minutes
            if (TimeBlocks.isBlock(startAt, endAt, zone)) {
                result.block = ParsedBlock(start, endAt.toLocalDateTime(zone))
                result.dueDate = LocalDateTime(due.date, DueDates.DATE_ONLY_TIME)
                result.dueDateHasTime = false
                consumed.add(length.start until length.end)
                result.tokens.add(
                    ParsedToken(
                        type = TokenType.LENGTH,
                        start = length.start,
                        end = length.end,
                        value = length.minutes,
                        raw = rawInput.substring(length.start, length.end),
                    ),
                )
            }
        }

        // 7. Build title from non-consumed regions
        result.title = buildTitle(rawInput, consumed)

        // 8. Leading/trailing/standalone ! -> today (when enabled and DATE is not suppressed; the
        // suppression gate makes dismissing the Today chip stick for bang-created dates). An
        // explicit date wins, but the "!" is still removed from the title.
        if (config.bangToday && TokenType.DATE !in suppress) {
            val bang = extractBangToday(result.title, now)
            if (bang.dueDate != null) {
                result.title = bang.title
                if (result.dueDate == null) {
                    result.dueDate = bang.dueDate
                    result.dueDateHasTime = false
                    addBangToken(result, rawInput, consumed, bang)
                }
            }
        }

        return result.toParseResult()
    }

    /**
     * The bang was found in the rebuilt title; map it back to the raw input as the first (leading)
     * or last (trailing/standalone) non-consumed, non-whitespace character so the field highlights
     * it like other tokens.
     */
    private fun addBangToken(
        result: MutableParseResult,
        rawInput: String,
        consumed: List<IntRange>,
        bang: BangTodayResult,
    ) {
        val free = { i: Int -> !rawInput[i].isWhitespace() && consumed.none { r -> i in r } }
        val bangIndex = when (bang.form) {
            BangForm.LEADING -> rawInput.indices.firstOrNull(free)
            BangForm.TRAILING, BangForm.STANDALONE -> rawInput.indices.lastOrNull(free)
            BangForm.NONE -> null // unreachable: the caller checked bang.dueDate
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

private class MutableParseResult(
    var title: String = "",
    var dueDate: LocalDateTime? = null,
    var dueDateHasTime: Boolean = false,
    var block: ParsedBlock? = null,
    var priority: Int? = null,
    var labels: List<String> = emptyList(),
    var project: String? = null,
    var recurrence: ParsedRecurrence? = null,
    var place: PlaceMatch? = null,
    val tokens: MutableList<ParsedToken> = mutableListOf(),
) {
    /**
     * The weekday of "every monday" is part of the recurrence, not a due date: the recurrence
     * token, which covered only "every", now covers the weekday too.
     */
    fun claimWeekdayForRecurrence(rawInput: String, weekday: IntRange) {
        val index = tokens.indexOfFirst { it.type == TokenType.RECURRENCE }
        if (index < 0) return
        val token = tokens[index]
        tokens[index] = token.copy(end = weekday.last + 1, raw = rawInput.substring(token.start, weekday.last + 1))
    }

    fun toParseResult() = ParseResult(
        title = title,
        dueDate = dueDate,
        dueDateHasTime = dueDateHasTime,
        block = block,
        priority = priority,
        labels = labels,
        project = project,
        recurrence = recurrence,
        place = place,
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
        pos = maxOf(pos, region.last + 1)
    }
    if (pos < input.length) {
        sb.append(input, pos, input.length)
    }
    return sb.toString().replace(Regex("""\s+"""), " ").trim()
}
