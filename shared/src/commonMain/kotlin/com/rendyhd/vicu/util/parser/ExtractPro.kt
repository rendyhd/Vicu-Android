package com.rendyhd.vicu.util.parser

import com.rendyhd.vicu.util.pro.People
import com.rendyhd.vicu.util.pro.Person

/**
 * Pro's quick-add phrases (docs/cross-app-semantics-v1.md section 5.5), read only while Pro is on:
 * `req:` and a person's `Jan:` / `for Jan:` at the start, `~1h30` estimates and `!flag`. The start
 * date (`starts monday`) is read in [TaskParser], because it needs the date reader. The same rules
 * as the desktop's `extract-pro.ts`.
 */

/** The person a `Jan:` or `for Jan:` prefix names. [end] includes the spaces after the colon. */
internal class PersonPrefix(val id: Long, val mine: Boolean, val start: Int, val end: Int)

internal class ProExtraction<T>(val value: T, val tokens: List<ParsedToken>)

private fun overlaps(consumed: List<IntRange>, start: Int, end: Int): Boolean =
    consumed.any { start < it.last + 1 && end > it.first }

private val REQUEST_RE = Regex("""^\s*req:\s*""", RegexOption.IGNORE_CASE)

/** `req:` at the very start (after spaces). */
internal fun extractRequest(input: String, consumed: MutableList<IntRange>): ProExtraction<Boolean> {
    val match = REQUEST_RE.find(input)
    if (match == null || match.value.isBlank() || overlaps(consumed, 0, match.value.length)) {
        return ProExtraction(false, emptyList())
    }
    val start = match.value.length - match.value.trimStart().length
    val end = match.value.length
    consumed.add(start until end)
    return ProExtraction(
        true,
        listOf(ParsedToken(TokenType.REQUEST, start, end, true, match.value.trim())),
    )
}

private fun isNameStart(ch: Char): Boolean = ch.isLetter()
private fun isNameChar(ch: Char): Boolean = ch.isLetter() || ch == '\'' || ch == '.' || ch == '-'

/**
 * The name `[\p{L}][\p{L}'.-]*(?:\s+[\p{L}][\p{L}'.-]*){0,2}` followed by `:` and spaces, read at
 * [from] in [text]. Returns the name and the index after the spaces, or null.
 */
private fun readNameAndColon(text: String, from: Int): Pair<String, Int>? {
    var i = from
    var words = 0
    val nameStart = from
    while (true) {
        if (i >= text.length || !isNameStart(text[i])) return null
        while (i < text.length && isNameChar(text[i])) i++
        words++
        val nameEnd = i
        if (i < text.length && text[i] == ':') {
            var j = i + 1
            if (j >= text.length || !text[j].isWhitespace()) return null
            while (j < text.length && text[j].isWhitespace()) j++
            return text.substring(nameStart, nameEnd) to j
        }
        if (words >= 3) return null
        var j = i
        while (j < text.length && text[j].isWhitespace()) j++
        if (j == i) return null
        i = j
    }
}

/**
 * A known person's name and a colon at the start (after `req:`, when there is one), then a space.
 * The name is one to three words; it must match one person (section 21.5).
 */
internal fun extractPersonPrefix(
    input: String,
    people: List<Person>,
    consumed: MutableList<IntRange>,
): ProExtraction<PersonPrefix?> {
    if (people.isEmpty()) return ProExtraction(null, emptyList())
    val from = consumed
        .filter { it.first == 0 || input.substring(0, it.first).isBlank() }
        .fold(0) { max, region -> maxOf(max, region.last + 1) }
    val rest = input.substring(from)
    var lead = 0
    while (lead < rest.length && rest[lead].isWhitespace()) lead++
    var mine = false
    var read: Pair<String, Int>? = null
    if (rest.regionMatches(lead, "for", 0, 3, ignoreCase = true) && lead + 3 < rest.length && rest[lead + 3].isWhitespace()) {
        var k = lead + 3
        while (k < rest.length && rest[k].isWhitespace()) k++
        read = readNameAndColon(rest, k)
        mine = read != null
    }
    if (read == null) read = readNameAndColon(rest, lead)
    if (read == null) return ProExtraction(null, emptyList())
    val found = People.matchPerson(read.first, people)
    val id = found.id ?: return ProExtraction(null, emptyList())
    val start = from + lead
    val end = from + read.second
    if (overlaps(consumed, start, end)) return ProExtraction(null, emptyList())
    consumed.add(start until end)
    val person = PersonPrefix(id, mine, start, end)
    return ProExtraction(person, listOf(ParsedToken(TokenType.PERSON, start, end, person, input.substring(start, end).trim())))
}

private val ESTIMATE_RE = Regex(
    """(?:^|(?<=\s))~(?:(\d{1,2})h(?:(\d{1,2})(?:m|min)?)?|(\d{1,4})(?:m|min))(?=[\s.,!?;)]|$)""",
    RegexOption.IGNORE_CASE,
)

/** `~10m`, `~45min`, `~1h`, `~1h30`, `~1h30m`: minutes, 1 to 1440. */
internal fun extractEstimate(input: String, consumed: MutableList<IntRange>): ProExtraction<Int?> {
    for (match in ESTIMATE_RE.findAll(input)) {
        val start = match.range.first
        val end = match.range.last + 1
        if (overlaps(consumed, start, end)) continue
        val minutes = if (match.groupValues[3].isNotEmpty()) {
            match.groupValues[3].toInt()
        } else {
            match.groupValues[1].toInt() * 60 + (match.groupValues[2].toIntOrNull() ?: 0)
        }
        if (minutes < 1 || minutes > 1440) continue
        consumed.add(start until end)
        return ProExtraction(minutes, listOf(ParsedToken(TokenType.ESTIMATE, start, end, minutes, match.value)))
    }
    return ProExtraction(null, emptyList())
}

private val FLAG_RE = Regex("""(?:^|(?<=\s))!flag(?=[\s.,;)]|$)""", RegexOption.IGNORE_CASE)

/** The word `!flag`, any case. */
internal fun extractFlag(input: String, consumed: MutableList<IntRange>): ProExtraction<Boolean> {
    for (match in FLAG_RE.findAll(input)) {
        val start = match.range.first
        val end = match.range.last + 1
        if (overlaps(consumed, start, end)) continue
        consumed.add(start until end)
        return ProExtraction(true, listOf(ParsedToken(TokenType.FLAG, start, end, true, match.value)))
    }
    return ProExtraction(false, emptyList())
}

/** Where `starts` begins a start-date phrase: the word's start and the index right after its spaces. */
internal class StartsWords(val start: Int, val phrase: Int)

private val STARTS_RE = Regex("""(?:^|(?<=\s))starts\s+""", RegexOption.IGNORE_CASE)

internal fun findStartsWords(input: String, consumed: List<IntRange>): List<StartsWords> =
    STARTS_RE.findAll(input)
        .map { StartsWords(it.range.first, it.range.last + 1) }
        .filter { !overlaps(consumed, it.start, it.phrase) }
        .toList()
