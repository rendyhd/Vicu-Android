package com.rendyhd.vicu.ui.components.task

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.ui.theme.LocalVicuColors

// The priority mark of docs/design-system-v1.md: the shapes of test-fixtures/design-tokens-v1.json
// `priority`, drawn on a 14 x 14 grid exactly as the desktop does (src/shared/priority-mark-svg.ts).
// One to three ascending bars for low, medium and high (the unused slots stay as a faint outline of
// the three, so the level reads from the count); a rounded square with a "!" cut out of it for
// urgent and do now. The colour is the priority role; the name for assistive technology comes from
// priorityDescription, so the mark never carries meaning alone.

/** The shape of a priority mark; the names follow the fixture's `priority.levels[].mark`. */
internal enum class PriorityMarkKind(val token: String) {
    BARS_1("bars-1"),
    BARS_2("bars-2"),
    BARS_3("bars-3"),
    SQUARE_BANG("square-bang"),
}

/** The mark of a Vikunja priority: 1 to 3 bars, 4 and 5 the square; null (nothing shown) for 0 and anything else. */
internal fun priorityMarkKind(priority: Int): PriorityMarkKind? = when (priority) {
    1 -> PriorityMarkKind.BARS_1
    2 -> PriorityMarkKind.BARS_2
    3 -> PriorityMarkKind.BARS_3
    4, 5 -> PriorityMarkKind.SQUARE_BANG
    else -> null
}

/** One bar slot of a bars mark on the 14 grid; [on] is false for the faint slots a level does not fill. */
internal data class PriorityBar(val x: Float, val y: Float, val width: Float, val height: Float, val on: Boolean)

/** The side of the square grid every mark is drawn in. */
internal const val PRIORITY_MARK_GRID = 14f

/** The corner radius of a bar on the grid. */
internal const val PRIORITY_BAR_RADIUS = 1f

/** Opacity of the unused slots of a bars mark. */
internal const val PRIORITY_SLOT_ALPHA = 0.28f

private const val BAR_WIDTH = 3f
private val BAR_SLOTS = listOf(
    Triple(1.5f, 8f, 5f),
    Triple(5.5f, 5f, 8f),
    Triple(9.5f, 2f, 11f),
)

/** The three slots of a bars [kind] with [PriorityBar.on] set on the ones the level fills; empty for the square. */
internal fun priorityBars(kind: PriorityMarkKind): List<PriorityBar> {
    val filled = when (kind) {
        PriorityMarkKind.BARS_1 -> 1
        PriorityMarkKind.BARS_2 -> 2
        PriorityMarkKind.BARS_3 -> 3
        PriorityMarkKind.SQUARE_BANG -> return emptyList()
    }
    return BAR_SLOTS.mapIndexed { i, (x, y, h) -> PriorityBar(x, y, BAR_WIDTH, h, on = i < filled) }
}

/** The urgent mark on the grid: the square (1..13, radius 2), the bar of the "!" and its dot, cut out with the even-odd rule. */
private fun squareBangPath(scale: Float): Path = Path().apply {
    fillType = PathFillType.EvenOdd
    addRoundRect(RoundRect(Rect(1f * scale, 1f * scale, 13f * scale, 13f * scale), CornerRadius(2f * scale)))
    addRect(Rect(6.25f * scale, 3.25f * scale, 7.75f * scale, 8.25f * scale))
    addOval(Rect(7f * scale - 0.9f * scale, 10.5f * scale - 0.9f * scale, 7f * scale + 0.9f * scale, 10.5f * scale + 0.9f * scale))
}

/**
 * The priority mark of a task, 14 dp, spoken as its description ("High priority"); nothing for no
 * priority. Colour comes from the priority role of the theme.
 */
@Composable
internal fun PriorityMark(priority: Int, modifier: Modifier = Modifier) {
    val kind = priorityMarkKind(priority) ?: return
    val description = priorityDescription(priority) ?: return
    val color = priorityMarkColor(priority, LocalVicuColors.current) ?: return
    PriorityMarkShape(kind, color, modifier.padding(horizontal = 2.dp).clearAndSetSemantics { contentDescription = description })
}

@Composable
private fun PriorityMarkShape(kind: PriorityMarkKind, color: Color, modifier: Modifier) {
    Canvas(modifier = modifier.size(14.dp)) {
        val scale = size.width / PRIORITY_MARK_GRID
        if (kind == PriorityMarkKind.SQUARE_BANG) {
            drawPath(squareBangPath(scale), color)
        } else {
            for (bar in priorityBars(kind)) {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(bar.x * scale, bar.y * scale),
                    size = Size(bar.width * scale, bar.height * scale),
                    cornerRadius = CornerRadius(PRIORITY_BAR_RADIUS * scale),
                    alpha = if (bar.on) 1f else PRIORITY_SLOT_ALPHA,
                )
            }
        }
    }
}
