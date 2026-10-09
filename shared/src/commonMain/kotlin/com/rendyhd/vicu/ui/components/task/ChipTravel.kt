package com.rendyhd.vicu.ui.components.task

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.util.lerp
import com.rendyhd.vicu.ui.theme.VicuMotion
import com.rendyhd.vicu.util.parser.ParsedToken

/** Where a chip starts when it travels out of the word it was read from: a shift of its centre and a scale. */
data class TravelStart(val dx: Float, val dy: Float, val scale: Float)

/** How transparent the chip is at the start of its travel. */
internal const val TRAVEL_START_ALPHA = 0.4f

/** The smallest the chip is drawn at the start of its travel (it never starts smaller than half its size). */
internal const val TRAVEL_MIN_SCALE = 0.5f

/**
 * Where a chip starts when it travels out of the token it was read from (card 4.11b; the desktop
 * `travelStart`): the shift from the chip's centre to the token's centre, and the scale that makes
 * the chip as wide as the token (not below [TRAVEL_MIN_SCALE], not above 1). Both rectangles are in
 * the same coordinate space.
 */
fun travelStart(token: Rect, chip: Rect): TravelStart {
    val scale = if (chip.width > 0f) (token.width / chip.width).coerceIn(TRAVEL_MIN_SCALE, 1f) else 1f
    return TravelStart(
        dx = token.center.x - chip.center.x,
        dy = token.center.y - chip.center.y,
        scale = scale,
    )
}

/**
 * A chip that was read from the typed text travels out of the highlighted word into its place
 * (card 4.11b), over the `move` spring. [fromText] is true while the chip holds a value read from
 * the text; the travel runs when it turns true, not on later edits of the same word. [tokenRect]
 * gives the word's rectangle in root coordinates (null when it cannot be told: the chip then only
 * fades in). Every spec follows the system animator scale; at scale 0 the chip is simply in place,
 * which is the reduced variant.
 */
@Composable
internal fun Modifier.chipTravel(fromText: Boolean, tokenRect: () -> Rect?): Modifier {
    val progress = remember(fromText) { Animatable(if (fromText) 0f else 1f) }
    var start by remember(fromText) { mutableStateOf<TravelStart?>(null) }
    var chipBounds by remember { mutableStateOf<Rect?>(null) }
    val currentTokenRect by rememberUpdatedState(tokenRect)
    LaunchedEffect(progress) {
        if (!fromText) return@LaunchedEffect
        // One frame: the chip has its new label and size, and the title its highlight.
        withFrameNanos { }
        val token = currentTokenRect()
        val chip = chipBounds
        if (token != null && chip != null) start = travelStart(token, chip)
        progress.animateTo(1f, VicuMotion.move.spec())
    }
    return this
        .onGloballyPositioned { chipBounds = it.boundsInRoot() }
        .graphicsLayer {
            val p = progress.value
            if (p < 1f) {
                alpha = lerp(TRAVEL_START_ALPHA, 1f, p)
                start?.let { s ->
                    translationX = s.dx * (1f - p)
                    translationY = s.dy * (1f - p)
                    val scale = lerp(s.scale, 1f, p)
                    scaleX = scale
                    scaleY = scale
                }
            }
        }
}

/** The inset of the text inside the title field (the Material text field's own padding without a label), in dp. */
internal const val TITLE_FIELD_TEXT_INSET_DP = 16f

/**
 * The rectangle of [token] inside [title], in root coordinates, as the title field draws it: the
 * text laid out at the width of the field ([fieldBounds], its inset taken off) and the token's
 * first and last character joined (just the first character when the token wraps onto a second
 * line). Null when the token is empty or the field has no size yet.
 */
internal fun titleTokenRect(
    measurer: TextMeasurer,
    style: TextStyle,
    title: String,
    token: ParsedToken,
    fieldBounds: Rect?,
    inset: Float,
): Rect? {
    if (fieldBounds == null || fieldBounds.width <= 2 * inset) return null
    val start = token.start.coerceIn(0, title.length)
    val end = token.end.coerceIn(0, title.length)
    if (start >= end) return null
    val layout = measurer.measure(
        text = AnnotatedString(title),
        style = style,
        constraints = Constraints(maxWidth = (fieldBounds.width - 2 * inset).toInt()),
    )
    val first = layout.getBoundingBox(start)
    val last = layout.getBoundingBox(end - 1)
    val box = if (first.top == last.top) Rect(first.left, first.top, last.right, last.bottom) else first
    return box.translate(fieldBounds.left + inset, fieldBounds.top + inset)
}
