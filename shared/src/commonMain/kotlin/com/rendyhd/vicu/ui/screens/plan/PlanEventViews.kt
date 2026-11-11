package com.rendyhd.vicu.ui.screens.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.util.CalendarEvent
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.DateDisplayFormat
import com.rendyhd.vicu.util.parseHexColor
import kotlin.math.sqrt
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Below this an event keeps its second line for screen readers only (design-system 4.2). */
private val EventTallEnough = 40.dp

/** The calendar's colour: a fixed value on both themes that only tints, edges and marks. */
internal fun calendarColor(hex: String): Color = parseHexColor(hex) ?: Color(0xFF12A594)

/** "10:00–10:30", or "All day". */
internal fun eventTimeText(event: CalendarEvent, zone: TimeZone, format: DateDisplayFormat): String =
    if (event.allDay) "All day" else DateDisplay.formatBlockRange(event.start.toLocalDateTime(zone), event.end.toLocalDateTime(zone), format)

/**
 * What a screen reader says for an event: the title (or "Busy"), the time, the place, and
 * "Tentative" or "Free" when they apply (design-system 4.2).
 */
internal fun eventDescription(event: CalendarEvent, timeText: String): String = buildList {
    add(event.title)
    add(timeText)
    event.location?.let { add(it) }
    if (event.tentative) add("Tentative")
    if (!event.busy) add("Free")
    add("event")
}.joinToString(", ")

/** Diagonal stripes of the calendar colour at 135 degrees, 6 dp each (design-system 4.2). */
private fun Modifier.eventStripes(color: Color, strong: Float, weak: Float): Modifier = drawBehind {
    val period = 12.dp.toPx()
    val reach = period / sqrt(2f)
    val brush = Brush.linearGradient(
        0f to color.copy(alpha = strong),
        0.5f to color.copy(alpha = strong),
        0.5f to color.copy(alpha = weak),
        1f to color.copy(alpha = weak),
        start = Offset.Zero,
        end = Offset(reach, reach),
        tileMode = TileMode.Repeated,
    )
    drawRect(brush)
}

/** The hairline, dashed for a tentative event. */
private fun Modifier.eventOutline(color: Color, dashed: Boolean, shape: Shape, width: Dp): Modifier = drawBehind {
    val stroke = width.toPx()
    val outline: Outline = shape.createOutline(size, layoutDirection, this)
    drawOutline(
        outline = outline,
        color = color,
        style = Stroke(
            width = stroke,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null,
        ),
    )
}

/**
 * A timed event on the timeline (design-system 4.2): striped, with a left edge in the calendar
 * colour and no checkbox, so it never reads as a task. A press opens it; [gestures] carries the
 * press and hold of your own Events.
 */
@Composable
internal fun TimelineEventView(
    event: CalendarEvent,
    color: Color,
    timeText: String,
    height: Dp,
    lifted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    gestures: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    val tall = height >= EventTallEnough
    val line2 = buildList {
        add(timeText)
        event.location?.let { add(it) }
        if (event.tentative) add("Tentative")
        if (!event.busy) add("Free")
    }.joinToString(" · ")
    Row(
        modifier = modifier
            .clip(shape)
            .background(colors.background)
            .eventStripes(color, if (event.busy) 0.16f else 0.08f, if (event.busy) 0.08f else 0.04f)
            .eventOutline(if (lifted) color else colors.outlineVariant, event.tentative, shape, if (lifted) 2.dp else 1.dp)
            .clickable(onClickLabel = "Open") { onClick() }
            .then(gestures)
            .semantics {
                contentDescription = eventDescription(event, timeText)
                role = Role.Button
            }
            .padding(end = 4.dp),
        verticalAlignment = if (tall) Alignment.Top else Alignment.CenterVertically,
    ) {
        Box(Modifier.fillMaxHeight().width(3.dp).background(color.copy(alpha = if (event.busy) 1f else 0.6f)))
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f).padding(top = if (tall) 4.dp else 0.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Outlined.Event, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurface,
                    maxLines = if (tall) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (tall) {
                Text(
                    text = line2,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** An all-day event in the day's top row: a chip, before the tasks (design-system 4.2). */
@Composable
internal fun AllDayEventChip(
    event: CalendarEvent,
    color: Color,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    Row(
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(colors.background)
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color, shape)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = "Open") { onClick() } else Modifier)
            .semantics { contentDescription = eventDescription(event, "all day") }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Outlined.Event, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(
            text = event.title,
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 180.dp),
        )
    }
}
