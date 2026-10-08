package com.rendyhd.vicu.ui.components.shared

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.util.ProjectProgress

private val RingSize = 16.dp
private val RingStroke = 2.dp

/**
 * A small ring that fills clockwise from the top as a project's tasks get done; a finished
 * project is a full ring (the desktop sidebar draws the same). The colour never carries the
 * meaning alone: a screen reader says "3 of 8 done". It does not animate, so it has nothing to
 * reduce when the system animations are off.
 */
@Composable
fun ProgressRing(
    progress: ProjectProgress,
    color: Color,
    modifier: Modifier = Modifier,
    trackColor: Color = MaterialTheme.colorScheme.outlineVariant,
) {
    val label = progress.label
    Canvas(
        modifier = modifier
            .size(RingSize)
            .semantics { contentDescription = label },
    ) {
        val stroke = RingStroke.toPx()
        val topLeft = Offset(stroke / 2, stroke / 2)
        val arc = Size(size.width - stroke, size.height - stroke)
        drawArc(
            color = trackColor,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arc,
            style = Stroke(width = stroke),
        )
        if (progress.fraction > 0f) {
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * progress.fraction,
                useCenter = false,
                topLeft = topLeft,
                size = arc,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}
