package com.rendyhd.vicu.ui.screens.plan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.zIndex
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.components.assistant.dashedOutline
import com.rendyhd.vicu.ui.components.shared.LocalClockDay
import com.rendyhd.vicu.ui.components.shared.LocalDateFormat
import com.rendyhd.vicu.ui.theme.LocalVicuColors
import com.rendyhd.vicu.util.CalendarEvent
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.GestureRange
import com.rendyhd.vicu.util.PlacedBlock
import com.rendyhd.vicu.util.PlacedEvent
import com.rendyhd.vicu.util.PlannerHours
import com.rendyhd.vicu.util.PlannerTimeline
import com.rendyhd.vicu.util.TimeBlocks
import com.rendyhd.vicu.util.TimelineMarker
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/** One hour on the grid; 15 minutes is then 14 dp, and the shortest item stays readable. */
internal val PlanHourHeight = 56.dp
private val GutterWidth24h = 48.dp
private val GutterWidth12h = 62.dp

/** How far the hour labels and the width kept for them follow the text size. */
private const val HourLabelMaxScale = 1.5f

/** How far the day strip's labels follow the text size. */
internal const val StripLabelMaxScale = 1.2f

/** A label with no room to grow follows the text size only up to [max]. */
@Composable
internal fun TextStyle.withFontScaleAtMost(max: Float): TextStyle {
    val scale = LocalDensity.current.fontScale
    if (scale <= max) return this
    val shrink = max / scale
    return copy(
        fontSize = fontSize * shrink,
        lineHeight = if (lineHeight.isSpecified) lineHeight * shrink else lineHeight,
    )
}
private val ColumnEndPadding = 6.dp
private val BlockTallEnough = 40.dp

/** Below this a block has only its title, and its sheet has the check (the desktop's rule). */
private val BlockTooShortForCheck = 20.dp

/** Room for a line of reasons under the title and the time. */
private val BlockTallForReason = 58.dp

/** The touch rule, where a block leaves the room. */
private val TouchTarget = 48.dp

/** A narrow column keeps its title: the check takes less of it. */
private val NarrowCheckWidth = 32.dp
private val NarrowColumn = 120.dp

/** From this height the handle may lie half inside the block: its middle is still free for a press. */
private val HandleInsideFrom = 72.dp
private val ShortHandleInside = 12.dp

/** How far the handle stays from the middle of a block. */
private val HandleClearOfMiddle = 2.dp

/** How far a length handle reaches above and below the block's bottom edge. */
internal data class HandleReach(val inside: Dp, val below: Dp) {
    val height: Dp get() = inside + below
}

/**
 * The handle takes the full touch size where the block or the free room leaves it. It stops short of
 * the middle of this block and of the next one, which is where a tap or a press and hold goes: in a
 * stack of blocks it sits on the seam between them.
 */
internal fun handleReach(height: Dp, roomBelow: Dp, nextHeight: Dp?): HandleReach {
    val inside = if (height >= HandleInsideFrom) {
        TouchTarget / 2
    } else {
        minOf(ShortHandleInside, height / 2 - HandleClearOfMiddle).coerceAtLeast(HandleClearOfMiddle)
    }
    val free = TouchTarget - inside
    val below = if (nextHeight == null) {
        free
    } else {
        minOf(free, roomBelow + (nextHeight / 2 - HandleClearOfMiddle).coerceAtLeast(0.dp))
    }
    return HandleReach(inside, below)
}

/** Whether this block lies under the middle of [other]'s column. */
private fun PlacedBlock.liesUnderMiddleOf(other: PlacedBlock): Boolean {
    val middle = (other.column + 0.5f) / other.columnCount
    return middle >= column.toFloat() / columnCount && middle < (column + 1f) / columnCount
}

/** Room above the hour the grid opens on, so its label and first block are not cut off. */
internal val PlanOpeningMargin = 20.dp

/** The scroll position that opens the grid on [minute], with the margin above it. */
internal fun openingScroll(minute: Int, minutePx: Float, marginPx: Float): Int =
    (minute * minutePx - marginPx).coerceAtLeast(0f).roundToInt()

/** How close to the top or bottom of the viewport a dragged thing scrolls the grid. */
internal val PlanAutoScrollEdge = 56.dp

/** Pixels per frame at the very edge. */
internal const val PLAN_AUTO_SCROLL_SPEED = 14f

/** What a gesture on a block is doing: its stretch before, how far the finger went. */
private data class BlockGesture(
    val taskId: Long,
    val moving: Boolean,
    val base: GestureRange,
    val draggedPx: Float,
    val scrollAtStart: Int,
    /** The day on screen when it started: a move may end on another (the strip, contract 16.2). */
    val startDay: LocalDate,
)

/** What a gesture on one of your own Events is doing (contract 20.7): the block gestures, by event id. */
private data class EventGesture(
    val id: String,
    val moving: Boolean,
    val base: GestureRange,
    val draggedPx: Float,
    val scrollAtStart: Int,
)

/**
 * The timeline of one day. Read-only unless [canEdit] says otherwise (Today's Day view: it only opens
 * and ticks). A press and hold moves a block, its handle changes the length, both in 15-minute steps;
 * nothing is written before the finger comes up. While [onTapTime] is set, a tap anywhere on the grid
 * places the picked task. Blocks in [dashedIds] are a draft; while [draft] is on nothing is ticked.
 *
 * A block being moved reports the finger with [onBlockDrag] (null when it lets go), so the screen can
 * change the day under it; the block stays held while [day] changes. At the end [onBlockDropOutside]
 * may take the drop (a day of the strip, the top row) before [onMove] is asked. [scrollsUnder] says
 * whether a finger there scrolls the grid near its edges.
 */
@Composable
internal fun PlanTimeline(
    day: LocalDate,
    blocks: List<PlacedBlock>,
    markers: List<TimelineMarker>,
    routines: List<TimelineRoutine>,
    hours: PlannerHours,
    nowMinute: Int?,
    scrollState: ScrollState,
    colorOf: (Task) -> Color,
    heldDone: Set<Long>,
    onToggleDone: (Task) -> Unit,
    onBlockClick: (Task) -> Unit,
    onMarkerClick: (Task) -> Unit,
    modifier: Modifier = Modifier,
    canEdit: (Task) -> Boolean = { false },
    onMove: (Task, Int) -> Unit = { _, _ -> },
    onResize: (Task, Int) -> Unit = { _, _ -> },
    onTapTime: ((Int) -> Unit)? = null,
    dropPreview: GestureRange? = null,
    dropPreviewTitle: String = "",
    onGridPlaced: (LayoutCoordinates) -> Unit = {},
    onViewportPlaced: (LayoutCoordinates) -> Unit = {},
    dashedIds: Set<Long> = emptySet(),
    draft: Boolean = false,
    reasonOf: (Task) -> String? = { null },
    onBlockDrag: (Task, Offset?) -> Unit = { _, _ -> },
    onBlockDropOutside: (Task) -> Boolean = { false },
    scrollsUnder: (Offset) -> Boolean = { true },
    /**
     * Events beside the blocks (contract 20.7). Only the Plan screen passes them: never Today's Day
     * view. A press opens one; [canEditEvent] (your own Events) adds the block gestures, and the
     * stretch they end on goes to [onEventChange].
     */
    events: List<PlacedEvent> = emptyList(),
    eventColor: (String) -> Color = { Color.Unspecified },
    onEventClick: (CalendarEvent) -> Unit = {},
    canEditEvent: (CalendarEvent) -> Boolean = { false },
    onEventChange: (CalendarEvent, GestureRange) -> Unit = { _, _ -> },
    /** A press and hold on an empty time: something new there (a task or an Event). */
    onLongPressTime: ((Int) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val zone = LocalClockDay.current.zone
    val format = LocalDateFormat.current
    val haptics = LocalHapticFeedback.current
    val minutePx = with(density) { PlanHourHeight.toPx() } / 60f
    // The gutter fits the clock in use: "10:00 PM" takes more room than "22:00".
    val gutterWidth = (if (format.hour12) GutterWidth12h else GutterWidth24h) * density.fontScale.coerceIn(1f, HourLabelMaxScale)
    val colors = MaterialTheme.colorScheme
    val lineColor = colors.outlineVariant
    val offHoursColor = colors.onSurface.copy(alpha = 0.03f)
    val workStart = TimeBlocks.clockMinutes(hours.start)
    val workEnd = TimeBlocks.clockMinutes(hours.end)

    var gesture by remember { mutableStateOf<BlockGesture?>(null) }
    var eventGesture by remember { mutableStateOf<EventGesture?>(null) }
    // The block being moved, kept while another day is on screen: its gesture lives on it.
    var carried by remember { mutableStateOf<PlacedBlock?>(null) }
    val latestDay by rememberUpdatedState(day)
    val latestDrag by rememberUpdatedState(onBlockDrag)
    val latestDropOutside by rememberUpdatedState(onBlockDropOutside)
    val latestScrollsUnder by rememberUpdatedState(scrollsUnder)
    var viewport by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var fingerInWindow by remember { mutableStateOf<Offset?>(null) }
    val edgePx = with(density) { PlanAutoScrollEdge.toPx() }

    fun rangeOf(g: BlockGesture, scroll: Int): GestureRange {
        val delta = g.draggedPx + (scroll - g.scrollAtStart)
        return if (g.moving) {
            PlannerTimeline.movedRange(g.base, delta / minutePx.toDouble())
        } else {
            PlannerTimeline.endEdgeRange(g.base, g.base.endMinute + delta / minutePx.toDouble())
        }
    }

    fun eventRangeOf(g: EventGesture, scroll: Int): GestureRange {
        val delta = g.draggedPx + (scroll - g.scrollAtStart)
        return if (g.moving) {
            PlannerTimeline.movedRange(g.base, delta / minutePx.toDouble())
        } else {
            PlannerTimeline.endEdgeRange(g.base, g.base.endMinute + delta / minutePx.toDouble())
        }
    }

    // A block or an Event dragged near an edge scrolls the grid under it.
    val dragging = gesture != null || eventGesture != null
    LaunchedEffect(dragging) {
        if (!dragging) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val port = viewport ?: continue
            val finger = fingerInWindow ?: continue
            if (!latestScrollsUnder(finger)) continue
            scrollState.dispatchRawDelta(autoScrollDelta(port.windowToLocal(finger).y, port.size.height.toFloat(), edgePx))
        }
    }

    Box(
        modifier = modifier
            .onGloballyPositioned {
                viewport = it
                onViewportPlaced(it)
            }
            .verticalScroll(scrollState, enabled = !dragging),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(PlanHourHeight * 24)
                .onGloballyPositioned(onGridPlaced),
        ) {
            val columnsWidth = maxWidth - gutterWidth - ColumnEndPadding
            fun minuteDp(minute: Int): Dp = PlanHourHeight * (minute / 60f)

            Canvas(Modifier.fillMaxSize()) {
                val left = gutterWidth.toPx()
                // Outside the work hours is a little darker, so the working day stands out.
                drawRect(offHoursColor, Offset(left, 0f), androidx.compose.ui.geometry.Size(size.width - left, workStart * minutePx))
                drawRect(offHoursColor, Offset(left, workEnd * minutePx), androidx.compose.ui.geometry.Size(size.width - left, (24 * 60 - workEnd) * minutePx))
                for (hour in 0..24) {
                    val y = hour * 60 * minutePx
                    drawLine(lineColor, Offset(left, y), Offset(size.width, y), strokeWidth = 1f)
                }
                drawLine(lineColor, Offset(left, 0f), Offset(left, size.height), strokeWidth = 1f)
            }

            // A press and hold on an empty time; anything drawn over it takes its own presses first.
            if (onLongPressTime != null) {
                val latestLongPress by rememberUpdatedState(onLongPressTime)
                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onLongPress = { offset ->
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    val minute = PlannerTimeline.snapMinutes(offset.y / minutePx.toDouble() - TimeBlocks.STEP_MINUTES / 2.0)
                                    latestLongPress(minute.coerceIn(0, PlannerTimeline.DAY_MINUTES - TimeBlocks.STEP_MINUTES))
                                },
                            )
                        },
                )
            }

            for (hour in 1..23) {
                Text(
                    text = DateDisplay.formatTime(LocalTime(hour, 0), format),
                    style = MaterialTheme.typography.labelSmall.withFontScaleAtMost(HourLabelMaxScale),
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .offset(y = PlanHourHeight * hour - 8.dp)
                        .width(gutterWidth)
                        .padding(start = 6.dp, end = 2.dp),
                )
            }

            // Routines keep to the right edge and tasks with a due time to the left, unless a block
            // lies there: its title is at its left, so the pill moves right, above the block.
            for (routine in routines) {
                key(routine.key) {
                    Row(
                        modifier = Modifier
                            .offset(y = minuteDp(routine.minute) - 10.dp)
                            .zIndex(2f)
                            .fillMaxWidth()
                            .padding(end = ColumnEndPadding + 2.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TimelinePill(
                            text = routine.name,
                            done = routine.done,
                            color = LocalVicuColors.current.identity.routines,
                            maxWidth = 150.dp,
                            semanticsLabel = "Routine ${routine.name}",
                        )
                    }
                }
            }
            for (marker in markers) {
                key(marker.task.id) {
                    val overBlock = PlannerTimeline.markerOverBlock(marker.minute, blocks) ||
                        events.any { marker.minute + 10 > it.startMinute && marker.minute - 10 < it.startMinute + it.drawnMinutes }
                    Row(
                        modifier = Modifier
                            .offset(y = minuteDp(marker.minute) - 10.dp)
                            .zIndex(if (overBlock) 4f else 2f)
                            .fillMaxWidth()
                            .padding(start = gutterWidth + 2.dp, end = ColumnEndPadding + 2.dp),
                        horizontalArrangement = if (overBlock) Arrangement.End else Arrangement.Start,
                    ) {
                        TimelinePill(
                            text = marker.task.title,
                            done = false,
                            color = colorOf(marker.task),
                            maxWidth = columnsWidth * (if (overBlock) 0.45f else 0.7f),
                            semanticsLabel = "${marker.task.title}, due ${DateDisplay.formatTime(LocalTime(marker.minute / 60, marker.minute % 60), format)}",
                            leadingClock = true,
                            modifier = Modifier.clickable { onMarkerClick(marker.task) },
                        )
                    }
                }
            }

            for (placed in events) {
                key(placed.event.id) {
                    val event = placed.event
                    val editable = canEditEvent(event)
                    val current = eventGesture?.takeIf { it.id == event.id }
                    val range = current?.let { eventRangeOf(it, scrollState.value) }
                        ?: GestureRange(placed.startMinute, placed.endMinute)
                    val columnWidth = columnsWidth / placed.columnCount
                    val x = gutterWidth + columnWidth * placed.column
                    val y = minuteDp(range.startMinute)
                    // At least as tall as the shortest block, so its title fits; busy time keeps its real length.
                    val height = if (current != null) minuteDp(range.endMinute) - y else minuteDp(placed.drawnMinutes)
                    val latestPlaced by rememberUpdatedState(placed)
                    val latestChange by rememberUpdatedState(onEventChange)
                    val timeText = if (current != null) {
                        "${rangeText(day, range, zone, format)} · ${PlannerTimeline.formatLength(range.endMinute - range.startMinute)}"
                    } else {
                        eventTimeText(event, zone, format)
                    }
                    fun ownRange() = GestureRange(latestPlaced.startMinute, latestPlaced.endMinute)
                    TimelineEventView(
                        event = event,
                        color = eventColor(event.calendarId),
                        timeText = timeText,
                        height = height,
                        lifted = current != null,
                        onClick = { onEventClick(event) },
                        modifier = Modifier
                            .offset(x = x, y = y)
                            .zIndex(if (current != null) 4f else 1f)
                            .size(columnWidth - 2.dp, height - 1.dp),
                        gestures = if (editable) {
                            Modifier.pointerInput(event.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        eventGesture = EventGesture(event.id, moving = true, ownRange(), 0f, scrollState.value)
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        eventGesture = eventGesture?.let { it.copy(draggedPx = it.draggedPx + amount.y) }
                                    },
                                    onDragEnd = {
                                        val finished = eventGesture
                                        eventGesture = null
                                        if (finished != null) {
                                            val to = eventRangeOf(finished, scrollState.value)
                                            if (to != finished.base) latestChange(latestPlaced.event, to)
                                        }
                                    },
                                    onDragCancel = { eventGesture = null },
                                )
                            }
                        } else {
                            Modifier
                        },
                    )
                    // Your own Event's length handle, on its bottom edge like a block's.
                    if (editable) {
                        Box(
                            modifier = Modifier
                                .offset(x = x + (columnWidth - 2.dp - LengthHandleWidth) / 2, y = y + height - ShortHandleInside)
                                .zIndex(if (current != null) 5f else 3f)
                                .size(LengthHandleWidth, TouchTarget - ShortHandleInside)
                                .semantics { contentDescription = "Length of ${event.title}" }
                                .pointerInput(event.id) {
                                    detectVerticalDragGestures(
                                        onDragStart = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            eventGesture = EventGesture(event.id, moving = false, ownRange(), 0f, scrollState.value)
                                        },
                                        onVerticalDrag = { change, amount ->
                                            change.consume()
                                            val slop = if (eventGesture?.draggedPx == 0f) viewConfiguration.touchSlop * sign(amount) else 0f
                                            eventGesture = eventGesture?.let { it.copy(draggedPx = it.draggedPx + amount + slop) }
                                        },
                                        onDragEnd = {
                                            val finished = eventGesture
                                            eventGesture = null
                                            if (finished != null) {
                                                val to = eventRangeOf(finished, scrollState.value)
                                                if (to != finished.base) latestChange(latestPlaced.event, to)
                                            }
                                        },
                                        onDragCancel = { eventGesture = null },
                                    )
                                },
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            Box(
                                Modifier
                                    .padding(top = ShortHandleInside - 2.dp)
                                    .size(28.dp, 4.dp)
                                    .clip(CircleShape)
                                    .background(eventColor(event.calendarId)),
                            )
                        }
                    }
                }
            }

            val held = carried?.takeIf { gesture?.taskId == it.task.id && blocks.none { b -> b.task.id == it.task.id } }
            val shownBlocks = if (held != null) blocks + held else blocks
            for (placed in shownBlocks) {
                key(placed.task.id) {
                    val task = placed.task
                    val editable = canEdit(task) || gesture?.taskId == task.id
                    val current = gesture?.takeIf { it.taskId == task.id }
                    val range = current?.let { rangeOf(it, scrollState.value) }
                        ?: GestureRange(placed.startMinute, placed.endMinute)
                    val columnWidth = columnsWidth / placed.columnCount
                    val x = gutterWidth + columnWidth * placed.column
                    val y = minuteDp(range.startMinute)
                    // Drawn a little taller while short and the room is free; a gesture shows the real stretch.
                    val height = if (current != null) minuteDp(range.endMinute) - y else minuteDp(placed.drawnMinutes)
                    val latestPlaced by rememberUpdatedState(placed)
                    val latestMove by rememberUpdatedState(onMove)
                    val latestResize by rememberUpdatedState(onResize)
                    val done = task.done || task.id in heldDone
                    var blockCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

                    val minutesShown = if (current != null) range.endMinute - range.startMinute else placed.block.minutes
                    TimelineBlock(
                        task = task,
                        done = done,
                        timeText = "${rangeText(day, range, zone, format)} · ${PlannerTimeline.formatLength(minutesShown)}",
                        color = colorOf(task),
                        height = height,
                        wide = columnWidth >= NarrowColumn,
                        dashed = task.id in dashedIds,
                        showCheck = !draft && height >= BlockTooShortForCheck,
                        reason = reasonOf(task),
                        lifted = current != null,
                        onToggleDone = { onToggleDone(task) },
                        onClick = { onBlockClick(task) },
                        modifier = Modifier
                            .offset(x = x, y = y)
                            .zIndex(if (current != null) 4f else 1f)
                            .size(columnWidth - 2.dp, height - 1.dp)
                            .onGloballyPositioned { blockCoords = it },
                        gestures = if (editable) {
                                    Modifier.pointerInput(task.id) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { at ->
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                carried = latestPlaced
                                                gesture = BlockGesture(
                                                    task.id,
                                                    moving = true,
                                                    latestPlaced.ownRange(),
                                                    0f,
                                                    scrollState.value,
                                                    latestDay,
                                                )
                                                latestDrag(task, blockCoords?.localToWindow(at))
                                            },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                gesture = gesture?.let { it.copy(draggedPx = it.draggedPx + amount.y) }
                                                fingerInWindow = blockCoords?.localToWindow(change.position)
                                                latestDrag(task, fingerInWindow)
                                            },
                                            onDragEnd = {
                                                val finished = gesture
                                                gesture = null
                                                fingerInWindow = null
                                                carried = null
                                                if (finished != null && !latestDropOutside(task)) {
                                                    val to = rangeOf(finished, scrollState.value)
                                                    if (to != finished.base || latestDay != finished.startDay) latestMove(task, to.startMinute)
                                                }
                                                latestDrag(task, null)
                                            },
                                            onDragCancel = {
                                                gesture = null
                                                fingerInWindow = null
                                                carried = null
                                                latestDrag(task, null)
                                            },
                                        )
                                    }
                                } else {
                                    Modifier
                                },
                    )

                    // The length handle: on the block's bottom edge, so a short block can be grabbed by
                    // it and still has its middle for a tap or a press and hold.
                    if (editable) {
                        var handleCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
                        val drawnEnd = placed.startMinute + placed.drawnMinutes
                        val next = blocks.filter { it.startMinute >= drawnEnd && it.liesUnderMiddleOf(placed) }
                            .minByOrNull { it.startMinute }
                        val roomBelow = next?.let { minuteDp(it.startMinute - drawnEnd) }
                        val reach = handleReach(height, roomBelow ?: 0.dp, next?.let { minuteDp(it.drawnMinutes) })
                        val inside = reach.inside
                        // It lies over the block, and in a stack over the top of the next one: a tap
                        // here is a tap on the block under the finger.
                        val nextTop = next?.let { with(density) { (inside + (roomBelow ?: 0.dp)).toPx() } }
                        val latestNext by rememberUpdatedState(next)
                        val latestNextTop by rememberUpdatedState(nextTop)
                        val latestClick by rememberUpdatedState(onBlockClick)
                        Box(
                            modifier = Modifier
                                .offset(x = x + (columnWidth - 2.dp - LengthHandleWidth) / 2, y = y + height - inside)
                                .zIndex(if (current != null) 5f else 3f)
                                .size(LengthHandleWidth, reach.height)
                                .onGloballyPositioned { handleCoords = it }
                                .semantics { contentDescription = "Length of ${task.title}" }
                                .pointerInput(task.id) {
                                    detectTapGestures { offset ->
                                        val top = latestNextTop
                                        val below = latestNext
                                        latestClick(if (below != null && top != null && offset.y >= top) below.task else latestPlaced.task)
                                    }
                                }
                                .pointerInput(task.id) {
                                    detectVerticalDragGestures(
                                        onDragStart = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            gesture = BlockGesture(
                                                task.id,
                                                moving = false,
                                                latestPlaced.ownRange(),
                                                0f,
                                                scrollState.value,
                                                latestDay,
                                            )
                                        },
                                        onVerticalDrag = { change, amount ->
                                            change.consume()
                                            // The first event comes after the touch slop; the edge stays under the finger.
                                            val slop = if (gesture?.draggedPx == 0f) viewConfiguration.touchSlop * sign(amount) else 0f
                                            gesture = gesture?.let { it.copy(draggedPx = it.draggedPx + amount + slop) }
                                            fingerInWindow = handleCoords?.localToWindow(change.position)
                                        },
                                        onDragEnd = {
                                            val finished = gesture
                                            gesture = null
                                            fingerInWindow = null
                                            if (finished != null) {
                                                val to = rangeOf(finished, scrollState.value)
                                                if (to != finished.base) latestResize(task, to.endMinute)
                                            }
                                        },
                                        onDragCancel = {
                                            gesture = null
                                            fingerInWindow = null
                                        },
                                    )
                                },
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            Box(
                                Modifier
                                    .padding(top = inside - 2.dp)
                                    .size(28.dp, 4.dp)
                                    .clip(CircleShape)
                                    .background(colorOf(task)),
                            )
                        }
                    }
                }
            }

            if (dropPreview != null) {
                val y = minuteDp(dropPreview.startMinute)
                Column(
                    modifier = Modifier
                        .offset(x = gutterWidth, y = y)
                        .zIndex(4f)
                        .size(columnsWidth - 2.dp, minuteDp(dropPreview.endMinute) - y)
                        .clip(MaterialTheme.shapes.small)
                        .background(colors.primary.copy(alpha = 0.16f))
                        .border(1.dp, colors.primary, MaterialTheme.shapes.small)
                        .padding(horizontal = 8.dp),
                ) {
                    Text(
                        text = "${rangeText(day, dropPreview, zone, format)} · $dropPreviewTitle",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (nowMinute != null) {
                val errorColor = colors.error
                Canvas(
                    Modifier
                        .offset(x = gutterWidth - 4.dp, y = minuteDp(nowMinute) - 4.dp)
                        .zIndex(3f)
                        .size(maxWidth - gutterWidth + 4.dp, 8.dp),
                ) {
                    val middle = size.height / 2
                    drawCircle(errorColor, radius = middle, center = Offset(middle, middle))
                    drawLine(errorColor, Offset(middle, middle), Offset(size.width, middle), strokeWidth = 2.dp.toPx())
                }
            }

            // While a task is picked, a tap anywhere on the grid is the time for it.
            if (onTapTime != null) {
                val latestTap by rememberUpdatedState(onTapTime)
                Box(
                    Modifier
                        .matchParentSize()
                        .zIndex(6f)
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                val minute = PlannerTimeline.snapMinutes(offset.y / minutePx.toDouble())
                                latestTap(minute.coerceIn(0, PlannerTimeline.DAY_MINUTES - TimeBlocks.STEP_MINUTES))
                            }
                        },
                )
            }
        }
    }
}

private val LengthHandleWidth = 48.dp

/** The block's real stretch, whatever height it is drawn at. */
private fun PlacedBlock.ownRange(): GestureRange = GestureRange(startMinute, endMinute)

/** How far a finger at [y] in a viewport of [height] pixels scrolls the grid this frame. */
internal fun autoScrollDelta(y: Float, height: Float, edge: Float): Float = when {
    y < edge -> -PLAN_AUTO_SCROLL_SPEED * (1f - (y / edge).coerceIn(0f, 1f))
    y > height - edge -> PLAN_AUTO_SCROLL_SPEED * (1f - ((height - y) / edge).coerceIn(0f, 1f))
    else -> 0f
}

@Composable
private fun TimelineBlock(
    task: Task,
    done: Boolean,
    timeText: String,
    color: Color,
    height: Dp,
    wide: Boolean,
    dashed: Boolean,
    showCheck: Boolean,
    reason: String?,
    lifted: Boolean,
    onToggleDone: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    gestures: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    val tall = height >= BlockTallEnough
    val tiny = height < BlockTooShortForCheck
    Row(
        modifier = modifier
            .clip(shape)
            // The tint lies on the page colour, so the grid's lines do not run through the text.
            .background(colors.background)
            .background(color.copy(alpha = if (lifted) 0.32f else if (done) 0.08f else 0.18f))
            .then(
                if (dashed && !lifted) {
                    Modifier.dashedOutline(colors.primary, true)
                } else {
                    Modifier.border(if (lifted) 2.dp else 1.dp, color.copy(alpha = if (done) 0.35f else 0.7f), shape)
                },
            )
            .clickable(onClickLabel = "Open") { onClick() }
            // Inside the click, so a press and hold sees the gesture first and its end is no tap.
            .then(gestures)
            .semantics { contentDescription = "${task.title}, $timeText" }
            .padding(end = 4.dp),
        verticalAlignment = if (tall) Alignment.Top else Alignment.CenterVertically,
    ) {
        Box(Modifier.fillMaxHeight().width(3.dp).background(color.copy(alpha = if (done) 0.5f else 1f)))
        if (showCheck) {
            // As much of the touch size as the block and the column leave.
            Box(
                modifier = Modifier
                    .size(if (wide) TouchTarget else NarrowCheckWidth, minOf(height, TouchTarget))
                    .toggleable(value = done, role = Role.Checkbox, onValueChange = { onToggleDone() })
                    .semantics { contentDescription = if (done) "Mark ${task.title} not done" else "Complete ${task.title}" },
                contentAlignment = if (tall) Alignment.TopCenter else Alignment.Center,
            ) {
                Icon(
                    imageVector = if (done) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = null,
                    modifier = Modifier.padding(top = if (tall) 6.dp else 0.dp).size(16.dp),
                    tint = if (done) LocalVicuColors.current.done.color else color,
                )
            }
        } else {
            Spacer(Modifier.width(6.dp))
        }
        Column(modifier = Modifier.weight(1f).padding(top = if (tall) 4.dp else 0.dp)) {
            val titleStyle: TextStyle = if (tiny) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge
            Text(
                text = task.title,
                style = titleStyle,
                color = if (done) colors.onSurfaceVariant else colors.onSurface,
                textDecoration = if (done) TextDecoration.LineThrough else null,
                maxLines = if (tall) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (tall) {
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!reason.isNullOrEmpty() && height >= BlockTallForReason) {
                Text(
                    text = reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A routine or a task with a due time: a small pill at its time, with no length. */
@Composable
private fun TimelinePill(
    text: String,
    done: Boolean,
    color: Color,
    maxWidth: Dp,
    semanticsLabel: String,
    modifier: Modifier = Modifier,
    leadingClock: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .widthIn(max = maxWidth)
            .heightIn(min = 20.dp)
            .clip(CircleShape)
            .background(colors.surfaceContainerHigh)
            .border(1.dp, color.copy(alpha = 0.6f), CircleShape)
            .semantics { contentDescription = semanticsLabel }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingClock) {
            Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(12.dp), tint = color)
        } else {
            Icon(
                imageVector = if (done) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = color,
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (done) colors.onSurfaceVariant else colors.onSurface,
            textDecoration = if (done) TextDecoration.LineThrough else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
