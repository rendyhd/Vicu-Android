package com.rendyhd.vicu.ui.components.task

import io.github.linreal.cascade.editor.core.BlockType
import io.github.linreal.cascade.editor.core.SpanStyle

/**
 * The typing shortcuts of description format "Markdown input": what one typed character does to a
 * line. The patterns are the desktop editor's (TipTap's input rules plus Vicu's `1)`, `- [ ]` and
 * `[text](url)`), so a line typed the same way formats the same way on both.
 *
 * Cascade itself already turns `- ` and `1. ` into lists; those are left to it.
 */
internal object MarkdownShortcuts {

    sealed interface Plan

    /** Turn the block into [type] and remove the first [prefixLength] characters. */
    data class Convert(val type: BlockType, val prefixLength: Int) : Plan

    /** Replace the block (whose whole text is the marker) with a divider. */
    data object Divider : Plan

    /** Replace `[start, end)` with [text] and give it [style]. */
    data class Inline(val start: Int, val end: Int, val text: String, val style: SpanStyle) : Plan

    private val HEADING = Regex("^(#{1,6}) $")
    private val NUMBERED_PAREN = Regex("^([0-9]{1,9})\\) $")
    private val TASK = Regex("^\\[([ x])\\] $")
    private val CODE = Regex("^(?:```|~~~)[a-z]* $")
    private val RULE = Regex("^(?:---|\\*\\*\\* |___ )$")

    private val BOLD_STAR = Regex("(?:^|\\s)(\\*\\*(?!\\s+\\*\\*)([^*]+)\\*\\*(?!\\s+\\*\\*))$")
    private val BOLD_UNDERSCORE = Regex("(?:^|\\s)(__(?!\\s+__)([^_]+)__(?!\\s+__))$")
    private val ITALIC_STAR = Regex("(?:^|\\s)(\\*(?!\\s+\\*)([^*]+)\\*(?!\\s+\\*))$")
    private val ITALIC_UNDERSCORE = Regex("(?:^|\\s)(_(?!\\s+_)([^_]+)_(?!\\s+_))$")
    private val STRIKE = Regex("(?:^|\\s)(~~(?!\\s+~~)([^~]+)~~(?!\\s+~~))$")
    private val CODE_SPAN = Regex("(?:^|[^`])(`([^`]+)`)$")
    private val LINK = Regex("(?:^|\\s)(\\[([^\\[\\]]+)\\]\\(([^\\s()]+)\\))$")

    /**
     * What typing [inserted] so the cursor is at [cursor] does, in a block of [type] whose text is
     * now [text]; null when it is ordinary typing.
     */
    fun afterTyping(type: BlockType, text: String, cursor: Int, inserted: Char): Plan? {
        if (cursor <= 0 || cursor > text.length) return null
        val before = text.substring(0, cursor)
        blockPlan(type, before, text, inserted)?.let { return it }
        if (type is BlockType.Code || type is BlockType.Divider) return null
        return inlinePlan(before, inserted)
    }

    private fun blockPlan(type: BlockType, before: String, text: String, inserted: Char): Plan? {
        val paragraph = type is BlockType.Paragraph
        if (inserted == '-' && paragraph && before == "---" && text == "---") return Divider
        if (inserted != ' ') return null
        if (paragraph && RULE.matches(before) && text == before) return Divider
        if (paragraph) {
            HEADING.matchEntire(before)?.let { return Convert(BlockType.Heading(it.groupValues[1].length), before.length) }
            if (before == "> ") return Convert(BlockType.Quote, 2)
            if (before == "* " || before == "+ ") return Convert(BlockType.BulletList, 2)
            NUMBERED_PAREN.matchEntire(before)?.let { match ->
                val number = match.groupValues[1].toIntOrNull()
                if (number != null && number >= 1) return Convert(BlockType.NumberedList(number), before.length)
            }
            if (CODE.matches(before)) return Convert(BlockType.Code, before.length)
        }
        if (paragraph || type is BlockType.BulletList) {
            TASK.matchEntire(before)?.let { return Convert(BlockType.Todo(it.groupValues[1] == "x"), before.length) }
        }
        return null
    }

    private fun inlinePlan(before: String, inserted: Char): Plan? {
        val rules: List<Pair<Regex, SpanStyle>> = when (inserted) {
            '*' -> listOf(BOLD_STAR to SpanStyle.Bold, ITALIC_STAR to SpanStyle.Italic)
            '_' -> listOf(BOLD_UNDERSCORE to SpanStyle.Bold, ITALIC_UNDERSCORE to SpanStyle.Italic)
            '~' -> listOf(STRIKE to SpanStyle.StrikeThrough)
            '`' -> listOf(CODE_SPAN to SpanStyle.InlineCode)
            ')' -> {
                val match = LINK.find(before) ?: return null
                val href = MarkdownInput.markdownHref(match.groupValues[3]) ?: return null
                return Inline(before.length - match.groupValues[1].length, before.length, match.groupValues[2], SpanStyle.Link(href))
            }
            else -> return null
        }
        for ((regex, style) in rules) {
            val match = regex.find(before) ?: continue
            // Group 1 is the marked text, which ends at the cursor (every pattern ends in `$`).
            return Inline(before.length - match.groupValues[1].length, before.length, match.groupValues[2], style)
        }
        return null
    }
}
