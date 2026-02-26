package com.rendyhd.vicu.util.parser

data class LabelResult(
    val labels: List<String>,
    val tokens: List<ParsedToken>,
)

fun extractLabels(
    input: String,
    prefix: String,
    consumed: MutableList<IntRange>,
): LabelResult {
    val labels = mutableListOf<String>()
    val tokens = mutableListOf<ParsedToken>()

    val escapedPrefix = Regex.escape(prefix)

    // Quoted labels: @"multi word label" or @'multi word label'
    val quotedRe = Regex("""(?:^|(?<=\s))${escapedPrefix}(["'])(.+?)\1""")
    for (match in quotedRe.findAll(input)) {
        val label = match.groupValues[2].trim()
        if (label.isNotEmpty()) {
            val start = match.range.first
            val end = match.range.last + 1
            labels.add(label)
            consumed.add(start until end)
            tokens.add(
                ParsedToken(
                    type = TokenType.LABEL,
                    start = start,
                    end = end,
                    value = label,
                    raw = match.value,
                ),
            )
        }
    }

    // Unquoted labels: @word (must start after whitespace or beginning of string)
    val unquotedRe = Regex("""(?:^|(?<=\s))${escapedPrefix}([a-zA-Z][\w-]*)""")
    for (match in unquotedRe.findAll(input)) {
        val start = match.range.first
        val end = match.range.last + 1
        if (consumed.any { start < it.last + 1 && end > it.first }) continue
        val label = match.groupValues[1]
        labels.add(label)
        consumed.add(start until end)
        tokens.add(
            ParsedToken(
                type = TokenType.LABEL,
                start = start,
                end = end,
                value = label,
                raw = match.value,
            ),
        )
    }

    return LabelResult(labels, tokens)
}
