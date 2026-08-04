@file:OptIn(ExperimentalCascadeHtmlApi::class)

package com.rendyhd.vicu.ui.components.task

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.linreal.cascade.editor.core.Block
import io.github.linreal.cascade.editor.core.BlockId
import io.github.linreal.cascade.editor.htmlserialization.ExperimentalCascadeHtmlApi
import io.github.linreal.cascade.editor.htmlserialization.HtmlDecodeWarning
import io.github.linreal.cascade.editor.htmlserialization.HtmlSchema
import io.github.linreal.cascade.editor.serialization.resolveDocumentBlocks
import io.github.linreal.cascade.editor.state.BlockSpanStates
import io.github.linreal.cascade.editor.state.BlockTextStates
import io.github.linreal.cascade.editor.state.EditorState
import io.github.linreal.cascade.editor.state.EditorStateHolder

private const val LOCAL_ECHO_HISTORY_SIZE = 32

/**
 * Owns one Cascade runtime and coordinates it with a ViewModel-owned HTML string.
 */
@Stable
internal class DescriptionEditorSession(
    initialHtml: String,
    private val warningReporter: (String) -> Unit,
) {
    val textStates = BlockTextStates()
    val spanStates = BlockSpanStates()
    val stateHolder = EditorStateHolder()

    private var acceptedExternalHtml = initialHtml
    private var baselineBlocks: List<Block>
    private var baselineHtml: String? = null
    private var hostNeedsRepublish = false
    private var blockedImportedHtml by mutableStateOf<String?>(null)
    private val localHtmlEchoes = ArrayDeque<String>()

    val isImportBlocked: Boolean
        get() = blockedImportedHtml != null

    init {
        val decoded = HtmlSchema.decodeWithReport(
            html = initialHtml,
            profile = VikunjaDescriptionHtmlProfile.Profile,
        )
        reportDecodeWarnings(decoded.warnings)
        val inputLimitExceeded = decoded.warnings.any {
            it is HtmlDecodeWarning.InputLimitExceeded
        }
        if (inputLimitExceeded) {
            blockedImportedHtml = initialHtml
        }
        baselineBlocks = if (inputLimitExceeded) {
            emptyList<Block>().ensureEditableDocument()
        } else {
            decoded.blocks.ensureEditableDocument()
        }
        stateHolder.setState(EditorState.withBlocks(baselineBlocks))
        baselineBlocks = stateHolder.state.blocks
    }

    /**
     * Applies a genuine upstream document replacement.
     *
     * Returns false for identical values, locally emitted echoes, or an over-limit
     * import. The last case fails closed: it retains the exact host HTML and blocks
     * editing until the host supplies a supported replacement.
     */
    fun syncExternalHtml(html: String): Boolean {
        if (html == acceptedExternalHtml) return false
        if (html in localHtmlEchoes) {
            acceptedExternalHtml = html
            blockedImportedHtml = null
            hostNeedsRepublish = html != baselineHtml
            return false
        }

        val decoded = HtmlSchema.decodeWithReport(
            html = html,
            profile = VikunjaDescriptionHtmlProfile.Profile,
        )
        reportDecodeWarnings(decoded.warnings)
        if (decoded.warnings.any { it is HtmlDecodeWarning.InputLimitExceeded }) {
            blockedImportedHtml = html
            acceptedExternalHtml = html
            hostNeedsRepublish = false
            return false
        }

        val replacement = decoded.blocks.ensureEditableDocument()
        // Repository responses may normalize insignificant HTML whitespace.
        // Compare their canonical form too, otherwise an older response can look
        // "new", hard-reload the editor, and erase newer local typing.
        val canonicalReplacementHtml = encode(replacement)
        if (canonicalReplacementHtml in localHtmlEchoes) {
            acceptedExternalHtml = html
            blockedImportedHtml = null
            hostNeedsRepublish = canonicalReplacementHtml != baselineHtml
            return false
        }

        textStates.clear()
        spanStates.clear()
        stateHolder.setState(EditorState.withBlocks(replacement))
        baselineBlocks = stateHolder.state.blocks
        baselineHtml = null
        hostNeedsRepublish = false
        blockedImportedHtml = null
        acceptedExternalHtml = html
        localHtmlEchoes.clear()
        return true
    }

    fun resolveBlocks(): List<Block> =
        stateHolder.resolveDocumentBlocks(textStates, spanStates)

    /**
     * Publishes [blocks] only when their semantic editor content changed.
     *
     * Import canonicalization is therefore silent, while the first real user edit
     * emits canonical Vikunja HTML.
     */
    fun publishResolvedIfChanged(
        blocks: List<Block>,
        onHtmlChange: (String) -> Unit,
    ): Boolean {
        if (isImportBlocked) return false
        if (blocks == baselineBlocks && !hostNeedsRepublish) return false
        return publish(blocks, onHtmlChange)
    }

    /** Synchronous save barrier used by close/save/image actions. */
    fun flush(onHtmlChange: (String) -> Unit): Boolean =
        publishResolvedIfChanged(resolveBlocks(), onHtmlChange)

    /**
     * Returns current HTML and advances the change baseline.
     *
     * Image-token mutations use this to combine their new envelope with the latest
     * live editor text in one host update.
     */
    fun currentHtmlForHost(): String {
        blockedImportedHtml?.let { return it }
        val blocks = resolveBlocks()
        val encoded = encode(blocks)
        baselineBlocks = blocks
        // The host may emit this canonical HTML even when the document itself did
        // not change (for example, while removing an image token). Remember that
        // echo as local too, otherwise the canonicalized value looks like a genuine
        // upstream replacement and unnecessarily resets selection/history.
        baselineHtml = encoded
        hostNeedsRepublish = false
        rememberLocalEcho(encoded)
        return encoded
    }

    private fun publish(
        blocks: List<Block>,
        onHtmlChange: (String) -> Unit,
    ): Boolean {
        val html = encode(blocks)

        baselineBlocks = blocks
        baselineHtml = html
        hostNeedsRepublish = false
        rememberLocalEcho(html)
        onHtmlChange(html)
        return true
    }

    private fun encode(blocks: List<Block>): String {
        val encoded = HtmlSchema.encodeWithReport(
            blocks = blocks,
            profile = VikunjaDescriptionHtmlProfile.Profile,
        )
        if (encoded.warnings.isNotEmpty()) {
            warningReporter(
                "Cascade HTML export completed with ${encoded.warnings.size} warning(s)",
            )
        }
        return encoded.html
    }

    private fun rememberLocalEcho(html: String) {
        if (localHtmlEchoes.lastOrNull() == html) return
        localHtmlEchoes.addLast(html)
        while (localHtmlEchoes.size > LOCAL_ECHO_HISTORY_SIZE) {
            localHtmlEchoes.removeFirst()
        }
    }

    private fun reportDecodeWarnings(warnings: List<HtmlDecodeWarning>) {
        if (warnings.isEmpty()) return
        val limitExceeded = warnings.any { it is HtmlDecodeWarning.InputLimitExceeded }
        val summary = if (limitExceeded) {
            "Cascade HTML import rejected an over-limit description"
        } else {
            "Cascade HTML import completed with ${warnings.size} warning(s)"
        }
        warningReporter(summary)
    }
}

private fun List<Block>.ensureEditableDocument(): List<Block> {
    if (isEmpty()) return listOf(Block.paragraph())
    val seenIds = mutableSetOf<BlockId>()
    return map { block ->
        if (seenIds.add(block.id)) block else block.copy(id = BlockId.generate())
    }
}
