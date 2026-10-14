package com.rendyhd.vicu.auth

/**
 * Size policy for the on-device auth debug log. The log may grow [TRIM_SLACK] lines past
 * [MAX_LINES] before it is rewritten, so a trim happens once per [TRIM_SLACK] appends
 * instead of on every append.
 */
internal object AuthDebugLogRetention {
    const val MAX_LINES = 500
    const val TRIM_SLACK = 100

    fun shouldTrim(lineCount: Int): Boolean = lineCount > MAX_LINES + TRIM_SLACK

    fun trim(lines: List<String>): List<String> =
        if (lines.size > MAX_LINES) lines.takeLast(MAX_LINES) else lines
}
