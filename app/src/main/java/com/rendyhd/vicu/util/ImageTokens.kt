package com.rendyhd.vicu.util

/**
 * Kotlin port of desktop `src/renderer/lib/image-tokens.ts`.
 *
 * Image tokens let a task description reference Vikunja attachments inline:
 *   [[image:N]]            - uploaded attachment id N
 *   [[image-pending:uuid]] - staged image (pre-upload), Phase 2 only
 *
 * Normalized layout: tokens live at the end of the description, joined by `\n`,
 * separated from the plain-text body by a single `\n`. The textarea shows only
 * the plain-text body; the gallery renders the tokens.
 */
object ImageTokens {

    private val IMAGE_RE = Regex("""\[\[image:(\d+)]]""")
    private val PENDING_RE = Regex("""\[\[image-pending:([a-z0-9-]+)]]""")
    private val COMBINED_RE = Regex("""\[\[image:(\d+)]]|\[\[image-pending:([a-z0-9-]+)]]""")
    private val LEADING_TRAILING_NEWLINES = Regex("""^\n+|\n+$""")

    sealed class Segment {
        data class Text(val text: String) : Segment()
        data class Image(val attachmentId: Long) : Segment()
        data class Pending(val uuid: String) : Segment()
    }

    sealed class ImageRef {
        data class Image(val attachmentId: Long) : ImageRef()
        data class Pending(val uuid: String) : ImageRef()
    }

    /** Split text into ordered plain-text + token segments. */
    fun segmentDescription(text: String): List<Segment> {
        val result = mutableListOf<Segment>()
        var lastIndex = 0
        for (m in COMBINED_RE.findAll(text)) {
            val start = m.range.first
            if (start > lastIndex) {
                result.add(Segment.Text(text.substring(lastIndex, start)))
            }
            val imageId = m.groupValues[1]
            val pendingUuid = m.groupValues[2]
            when {
                imageId.isNotEmpty() -> result.add(Segment.Image(imageId.toLong()))
                pendingUuid.isNotEmpty() -> result.add(Segment.Pending(pendingUuid))
            }
            lastIndex = m.range.last + 1
        }
        if (lastIndex < text.length) {
            result.add(Segment.Text(text.substring(lastIndex)))
        }
        return result
    }

    /** Image refs found in the text, in positional order. */
    fun findImageRefs(text: String): List<ImageRef> =
        segmentDescription(text).mapNotNull { seg ->
            when (seg) {
                is Segment.Image -> ImageRef.Image(seg.attachmentId)
                is Segment.Pending -> ImageRef.Pending(seg.uuid)
                is Segment.Text -> null
            }
        }

    fun imageToken(attachmentId: Long): String = "[[image:$attachmentId]]"
    fun pendingToken(uuid: String): String = "[[image-pending:$uuid]]"

    /** Replace `[[image-pending:uuid]]` tokens with `[[image:id]]` per mapping. */
    fun replacePendingTokens(text: String, mapping: Map<String, Long>): String =
        PENDING_RE.replace(text) { m ->
            val uuid = m.groupValues[1]
            mapping[uuid]?.let { imageToken(it) } ?: m.value
        }

    /**
     * Strip tokens and return (plain text, ordered image refs).
     * Leading/trailing newlines on the plain text are trimmed.
     */
    fun parseValue(value: String): Pair<String, List<ImageRef>> {
        val textBuilder = StringBuilder()
        val refs = mutableListOf<ImageRef>()
        for (seg in segmentDescription(value)) {
            when (seg) {
                is Segment.Text -> textBuilder.append(seg.text)
                is Segment.Image -> refs.add(ImageRef.Image(seg.attachmentId))
                is Segment.Pending -> refs.add(ImageRef.Pending(seg.uuid))
            }
        }
        val text = textBuilder.toString().replace(LEADING_TRAILING_NEWLINES, "")
        return text to refs
    }

    /** Join plain text + token list, with a single newline separator when both present. */
    fun buildValue(text: String, images: List<ImageRef>): String {
        val tokens = images.joinToString("\n") { ref ->
            when (ref) {
                is ImageRef.Image -> imageToken(ref.attachmentId)
                is ImageRef.Pending -> pendingToken(ref.uuid)
            }
        }
        return when {
            tokens.isEmpty() -> text
            text.isEmpty() -> tokens
            else -> "$text\n$tokens"
        }
    }

    /** Convenience: append a new image token to the end of a description. */
    fun appendImageToken(value: String, attachmentId: Long): String {
        val (text, refs) = parseValue(value)
        return buildValue(text, refs + ImageRef.Image(attachmentId))
    }
}
