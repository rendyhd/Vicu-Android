@file:OptIn(ExperimentalCascadeHtmlApi::class)

package com.rendyhd.vicu.ui.components.task

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import io.github.linreal.cascade.editor.action.ConvertBlockType
import io.github.linreal.cascade.editor.action.FocusBlock
import io.github.linreal.cascade.editor.action.InsertBlockAfter
import io.github.linreal.cascade.editor.action.ReplaceBlock
import io.github.linreal.cascade.editor.action.UpdateBlockContent
import io.github.linreal.cascade.editor.core.Block
import io.github.linreal.cascade.editor.core.BlockContent
import io.github.linreal.cascade.editor.core.BlockId
import io.github.linreal.cascade.editor.core.BlockType
import io.github.linreal.cascade.editor.core.TextSpan
import io.github.linreal.cascade.editor.htmlserialization.ExperimentalCascadeHtmlApi
import io.github.linreal.cascade.editor.htmlserialization.HtmlSchema

/**
 * Markdown typed or pasted into the notes (description format, "Markdown input").
 *
 * Every change starts with one structural action through [dispatchStructuralAction], which records
 * the document as it was, so the toolbar's Undo puts back exactly what was typed or pasted over;
 * the live text and spans are adjusted after it. (Cascade groups several steps into one history
 * entry only internally, so Redo after such an Undo may come back without the cleanup.)
 */
internal class MarkdownEditing(private val session: DescriptionEditorSession) {

    private val holder get() = session.stateHolder
    private val textStates get() = session.textStates
    private val spanStates get() = session.spanStates

    /** True while this class changes the text, so the typing watcher does not read its own edits. */
    var applying = false
        private set

    fun applyTyping(blockId: BlockId, plan: MarkdownShortcuts.Plan, cursor: Int) {
        val block = holder.state.getBlock(blockId) ?: return
        applying = true
        try {
            when (plan) {
                is MarkdownShortcuts.Convert -> {
                    holder.dispatchStructuralAction(ConvertBlockType(blockId, plan.type), textStates, spanStates)
                    spanStates.adjustForRangeReplacement(blockId, 0, plan.prefixLength, 0)
                    val text = textStates.replaceVisibleRange(
                        blockId = blockId,
                        start = 0,
                        endExclusive = plan.prefixLength,
                        replacement = "",
                        cursorPositionAfter = (cursor - plan.prefixLength).coerceAtLeast(0),
                    ) ?: return
                    if (!plan.type.supportsSpans) spanStates.set(blockId, emptyList(), text.length)
                    val spans = if (plan.type.supportsSpans) spanStates.getSpans(blockId) else emptyList()
                    holder.dispatch(UpdateBlockContent(blockId, BlockContent.Text(text, spans)))
                }

                MarkdownShortcuts.Divider -> {
                    val divider = Block.divider()
                    val next = Block.paragraph()
                    holder.dispatchStructuralAction(ReplaceBlock(blockId, divider), textStates, spanStates)
                    holder.dispatch(InsertBlockAfter(next, divider.id))
                    textStates.getOrCreate(next.id, "", 0)
                    holder.dispatch(FocusBlock(next.id))
                }

                is MarkdownShortcuts.Inline -> {
                    val current = textStates.getVisibleText(blockId) ?: return
                    val text = current.substring(0, plan.start) + plan.text + current.substring(plan.end)
                    holder.dispatchStructuralAction(
                        UpdateBlockContent(blockId, BlockContent.Text(text, (block.content as? BlockContent.Text)?.spans.orEmpty())),
                        textStates,
                        spanStates,
                    )
                    spanStates.adjustForRangeReplacement(blockId, plan.start, plan.end, plan.text.length)
                    val replaced = textStates.replaceVisibleRange(
                        blockId = blockId,
                        start = plan.start,
                        endExclusive = plan.end,
                        replacement = plan.text,
                        cursorPositionAfter = plan.start + plan.text.length + (cursor - plan.end).coerceAtLeast(0),
                    ) ?: return
                    val styledEnd = plan.start + plan.text.length
                    spanStates.applyStyle(blockId, plan.start, styledEnd, plan.style, replaced.length)
                    // The next character is typed without the new style, like on the desktop (Cascade
                    // would otherwise continue the style of the character before the cursor).
                    spanStates.setPendingStyles(blockId, spanStates.activeStylesAt(blockId, styledEnd - 1) - plan.style)
                    holder.dispatch(UpdateBlockContent(blockId, BlockContent.Text(replaced, spanStates.getSpans(blockId))))
                }
            }
        } finally {
            applying = false
        }
    }

    /**
     * Inserts [html] (Markdown input converted to description HTML) at the cursor of the focused
     * text block: one paragraph into the line, anything else as blocks after the text before the
     * cursor, with the text after it on a line of its own. False when there is nowhere to put it.
     */
    fun paste(html: String): Boolean {
        val focusedId = holder.state.focusedBlockId ?: return false
        val focused = holder.state.getBlock(focusedId) ?: return false
        if (!focused.type.supportsText || focused.type is BlockType.Code) return false
        val current = textStates.getVisibleText(focusedId) ?: return false
        val selection = textStates.getSelection(focusedId) ?: return false
        val start = minOf(selection.start, selection.end).coerceIn(0, current.length)
        val end = maxOf(selection.start, selection.end).coerceIn(0, current.length)

        val pasted = HtmlSchema.decodeWithReport(html = html, profile = VikunjaDescriptionHtmlProfile.Profile).blocks
        if (pasted.isEmpty()) return false

        applying = true
        try {
            val single = pasted.singleOrNull()
            val singleContent = single?.content as? BlockContent.Text
            if (single != null && single.type is BlockType.Paragraph && singleContent != null) {
                pasteInline(focused, current, start, end, singleContent)
            } else {
                pasteBlocks(focused, current, start, end, pasted)
            }
        } finally {
            applying = false
        }
        return true
    }

    private fun pasteInline(focused: Block, current: String, start: Int, end: Int, content: BlockContent.Text) {
        val id = focused.id
        val text = current.substring(0, start) + content.text + current.substring(end)
        holder.dispatchStructuralAction(
            UpdateBlockContent(id, BlockContent.Text(text, (focused.content as? BlockContent.Text)?.spans.orEmpty())),
            textStates,
            spanStates,
        )
        spanStates.adjustForRangeReplacement(id, start, end, content.text.length)
        val replaced = textStates.replaceVisibleRange(id, start, end, content.text, start + content.text.length) ?: return
        if (focused.type.supportsSpans) {
            for (span in content.spans) {
                spanStates.applyStyle(id, start + span.start, start + span.end, span.style, replaced.length)
            }
        }
        holder.dispatch(UpdateBlockContent(id, BlockContent.Text(replaced, spanStates.getSpans(id))))
    }

    private fun pasteBlocks(focused: Block, current: String, start: Int, end: Int, pasted: List<Block>) {
        val id = focused.id
        val keepText = current.substring(0, start)
        val afterText = current.substring(end)
        val afterSpans = spanStates.getSpans(id)
            .filter { it.end > end }
            .map { TextSpan(maxOf(it.start, end) - end, it.end - end, it.style) }

        // An empty plain line is taken over by the paste; otherwise its text before the cursor stays.
        val replaceFocused = keepText.isEmpty() && focused.type is BlockType.Paragraph
        val placed = mutableListOf<Block>()
        if (replaceFocused) {
            holder.dispatchStructuralAction(ReplaceBlock(id, pasted.first()), textStates, spanStates)
            placed += pasted.first()
        } else {
            holder.dispatchStructuralAction(InsertBlockAfter(pasted.first(), id), textStates, spanStates)
            placed += pasted.first()
            spanStates.adjustForRangeReplacement(id, start, current.length, 0)
            val trimmed = textStates.replaceVisibleRange(id, start, current.length, "", start)
            if (trimmed != null) holder.dispatch(UpdateBlockContent(id, BlockContent.Text(trimmed, spanStates.getSpans(id))))
        }
        for (block in pasted.drop(1)) {
            holder.dispatch(InsertBlockAfter(block, placed.last().id))
            placed += block
        }
        if (afterText.isNotEmpty()) {
            val rest = Block.paragraph(afterText, afterSpans)
            holder.dispatch(InsertBlockAfter(rest, placed.last().id))
        }

        // The cursor goes to the end of the last pasted line that holds text.
        val last = placed.lastOrNull { it.type.supportsText } ?: return
        val lastText = (last.content as? BlockContent.Text)?.text.orEmpty()
        textStates.getOrCreate(last.id, lastText, lastText.length)
        textStates.setCursorPosition(last.id, lastText.length)
        holder.dispatch(FocusBlock(last.id))
    }
}

/**
 * Watches what is typed in the focused line and applies the Markdown typing shortcuts. Only a
 * single typed character counts; pastes and this class's own edits do not.
 */
@Composable
internal fun MarkdownTypingEffect(session: DescriptionEditorSession, editing: MarkdownEditing) {
    LaunchedEffect(session, editing) {
        var lastId: BlockId? = null
        var lastText: String? = null
        snapshotFlow {
            val id = session.stateHolder.state.focusedBlockId
            id to id?.let { session.textStates.getVisibleText(it) }
        }.collect { (id, visible) ->
            val previous = lastText
            val sameBlock = id != null && id == lastId
            lastId = id
            lastText = visible
            if (!sameBlock || visible == null || previous == null || editing.applying) return@collect
            if (visible.length != previous.length + 1) return@collect
            val selection = session.textStates.getSelection(id!!) ?: return@collect
            if (selection.start != selection.end) return@collect
            val cursor = selection.end
            if (cursor < 1 || cursor > visible.length) return@collect
            // One character inserted just before the cursor, the rest unchanged.
            if (visible.substring(0, cursor - 1) + visible.substring(cursor) != previous) return@collect
            val block = session.stateHolder.state.getBlock(id) ?: return@collect
            val plan = MarkdownShortcuts.afterTyping(block.type, visible, cursor, visible[cursor - 1]) ?: return@collect
            editing.applyTyping(id, plan, cursor)
            lastText = session.textStates.getVisibleText(id)
            lastId = session.stateHolder.state.focusedBlockId
            if (lastId != id) lastText = lastId?.let { session.textStates.getVisibleText(it) }
        }
    }
}
