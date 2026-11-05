package com.rendyhd.vicu.util.parser

import com.rendyhd.vicu.util.TimeBlocks

/**
 * The `for` phrases of the quick-add parser (docs/cross-app-semantics-v1.md section 5.4): `for 30m`,
 * `for 90 min`, `for 2 hours`, `for 1h30m` are lengths; `for 3 days`, `for 2 nights`, `for 3 months`
 * are not, and are never dates either. Finding them is the same on the desktop (`findForPhrases`).
 */
internal class ForLength(val start: Int, val end: Int, val minutes: Int)

internal class ForPhrases(
    /** Every phrase: dates are read as if these words were not there, so they never split a date. */
    val hidden: List<IntRange>,
    /** The first usable length: 1 minute to 12 hours. Whether it makes a block depends on the date. */
    val length: ForLength?,
)

private const val HOURS = "(?:hours?|hrs?|h)"
private const val MINUTES = "(?:minutes?|mins?|m)"
private const val LENGTH = """(?:(\d+)\s*$HOURS(?:\s*(\d+)\s*$MINUTES)?|(\d+)\s*$MINUTES)"""
private const val OTHER_UNIT = """\d+\s*(?:days?|nights?|weeks?|months?|years?)"""
private val FOR_PHRASE_RE =
    Regex("""(?:^|(?<=\s))for\s+(?:$LENGTH|$OTHER_UNIT)(?=[\s.,!?;)]|$)""", RegexOption.IGNORE_CASE)

internal fun findForPhrases(input: String, consumed: List<IntRange>): ForPhrases {
    val hidden = mutableListOf<IntRange>()
    var length: ForLength? = null
    for (match in FOR_PHRASE_RE.findAll(input)) {
        val start = match.range.first
        val end = match.range.last + 1
        if (consumed.any { start < it.last + 1 && end > it.first }) continue
        hidden += start until end
        if (length != null) continue
        val minutes = minutesOf(match.groupValues[1], match.groupValues[2], match.groupValues[3]) ?: continue
        if (minutes in 1..TimeBlocks.MAX_BLOCK_MINUTES) length = ForLength(start, end, minutes.toInt())
    }
    return ForPhrases(hidden, length)
}

/** The minutes of a phrase; null when it is no length or too long to read. */
private fun minutesOf(hours: String, minutesAfterHours: String, minutes: String): Long? = when {
    minutes.isNotEmpty() -> minutes.toLongOrNull()
    hours.isNotEmpty() -> {
        val h = hours.toLongOrNull()
        val m = if (minutesAfterHours.isEmpty()) 0L else minutesAfterHours.toLongOrNull()
        if (h == null || m == null || h > TimeBlocks.MAX_BLOCK_MINUTES) null else h * 60 + m
    }
    else -> null
}
