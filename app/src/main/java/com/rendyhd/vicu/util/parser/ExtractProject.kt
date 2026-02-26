package com.rendyhd.vicu.util.parser

data class ProjectResult(
    val project: String?,
    val tokens: List<ParsedToken>,
)

fun extractProject(
    input: String,
    prefix: String,
    consumed: MutableList<IntRange>,
): ProjectResult {
    val tokens = mutableListOf<ParsedToken>()
    val escapedPrefix = Regex.escape(prefix)

    // Quoted project: #"multi word"
    val quotedRe = Regex("""(?:^|(?<=\s))${escapedPrefix}(["'])(.+?)\1""")
    val quotedMatch = quotedRe.find(input)
    if (quotedMatch != null) {
        val start = quotedMatch.range.first
        val end = quotedMatch.range.last + 1
        if (!consumed.any { start < it.last + 1 && end > it.first }) {
            val project = quotedMatch.groupValues[2].trim()
            if (project.isNotEmpty()) {
                consumed.add(start until end)
                tokens.add(
                    ParsedToken(
                        type = TokenType.PROJECT,
                        start = start,
                        end = end,
                        value = project,
                        raw = quotedMatch.value,
                    ),
                )
                return ProjectResult(project, tokens)
            }
        }
    }

    // Unquoted project: #word (prefix + letter, not digit)
    val unquotedRe = Regex("""(?:^|(?<=\s))${escapedPrefix}([a-zA-Z][\w-]*)""")
    for (match in unquotedRe.findAll(input)) {
        val start = match.range.first
        val end = match.range.last + 1
        if (consumed.any { start < it.last + 1 && end > it.first }) continue
        val project = match.groupValues[1]
        consumed.add(start until end)
        tokens.add(
            ParsedToken(
                type = TokenType.PROJECT,
                start = start,
                end = end,
                value = project,
                raw = match.value,
            ),
        )
        return ProjectResult(project, tokens)
    }

    return ProjectResult(null, tokens)
}
