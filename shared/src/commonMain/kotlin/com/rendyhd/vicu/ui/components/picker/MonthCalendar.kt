package com.rendyhd.vicu.ui.components.picker

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.ui.components.shared.LocalClockDay
import com.rendyhd.vicu.ui.components.shared.LocalDateFormat
import com.rendyhd.vicu.ui.theme.VicuMotion
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.DateDisplayFormat
import com.rendyhd.vicu.util.GridDay
import com.rendyhd.vicu.util.WeekStart
import com.rendyhd.vicu.util.WhenLogic
import kotlinx.datetime.LocalDate
import kotlin.math.abs

/**
 * A month of days in six weeks that start on the account's week start ([WhenLogic.monthGrid]):
 * the calendar of the When sheet and the reminder picker, the same grid as the desktop When panel.
 * Material's date picker starts its weeks where the device's region does (on iOS it reads nothing
 * else), while Vicu's weeks start on the day the account chose, Monday unless it chose another
 * (contract section 2.1).
 *
 * The shown month follows [selected] whenever it changes (a quick choice, typed text, a dimmed day
 * of the next month); the arrows and a horizontal swipe move it. Each day is a 48 dp target read
 * aloud as its full date.
 *
 * A calendar that shows something about each day (a routine's history) draws the days itself with
 * [dayContent] and says what it drew in [dayNote], which is read after the date.
 */
@Composable
fun MonthCalendar(
    selected: LocalDate?,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    dayNote: (LocalDate) -> String? = { null },
    dayContent: (@Composable (day: GridDay, isSelected: Boolean, isToday: Boolean) -> Unit)? = null,
) {
    val clockDay = LocalClockDay.current
    val today = clockDay.date
    val fmt = LocalDateFormat.current
    var month by remember { mutableStateOf(WhenLogic.monthStart(selected ?: today)) }
    LaunchedEffect(selected) {
        if (selected != null) month = WhenLogic.monthStart(selected)
    }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    Column(modifier) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = DateDisplay.formatMonthYear(month, fmt),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            IconButton(onClick = { month = WhenLogic.addMonths(month, -1) }) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = "Previous month")
            }
            IconButton(onClick = { month = WhenLogic.addMonths(month, 1) }) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "Next month")
            }
        }

        // A month change only fades: there is no movement to reduce.
        Crossfade(
            targetState = month,
            animationSpec = tween(VicuMotion.fadeFastMs),
            label = "month",
            modifier = Modifier.pointerInput(rtl) {
                val threshold = 48.dp.toPx()
                var drag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { drag = 0f },
                    onDragEnd = {
                        if (abs(drag) >= threshold) {
                            val forward = if (rtl) drag > 0 else drag < 0
                            month = WhenLogic.addMonths(month, if (forward) 1 else -1)
                        }
                    },
                ) { change, amount ->
                    change.consume()
                    drag += amount
                }
            },
        ) { shown ->
            MonthGrid(
                month = shown,
                weekStart = clockDay.weekStart,
                selected = selected,
                today = today,
                fmt = fmt,
                onSelect = onSelect,
                dayNote = dayNote,
                dayContent = dayContent,
            )
        }
    }
}

@Composable
private fun MonthGrid(
    month: LocalDate,
    weekStart: WeekStart,
    selected: LocalDate?,
    today: LocalDate,
    fmt: DateDisplayFormat,
    onSelect: (LocalDate) -> Unit,
    dayNote: (LocalDate) -> String?,
    dayContent: (@Composable (day: GridDay, isSelected: Boolean, isToday: Boolean) -> Unit)?,
) {
    val weeks = remember(month, weekStart) { WhenLogic.monthGrid(month, weekStart) }
    Column(Modifier.fillMaxWidth()) {
        // Every day reads its own weekday aloud, so the column names stay out of the reading order.
        Row(Modifier.fillMaxWidth().clearAndSetSemantics { }) {
            for (day in weeks.first()) {
                Text(
                    text = DateDisplay.formatWeekdayShort(day.date, fmt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                )
            }
        }
        for (week in weeks) {
            Row(Modifier.fillMaxWidth()) {
                for (day in week) {
                    val isToday = day.date == today
                    val full = DateDisplay.formatFullDateWithYear(day.date, fmt)
                    val named = if (isToday) "Today, $full" else full
                    DayCell(
                        day = day,
                        isSelected = day.date == selected,
                        isToday = isToday,
                        label = dayNote(day.date)?.let { "$named, $it" } ?: named,
                        onClick = { onSelect(day.date) },
                        modifier = Modifier.weight(1f),
                        content = dayContent,
                    )
                }
            }
        }
    }
}

/**
 * A day: filled when picked, ringed when it is today, dimmed outside the shown month. With
 * [content] the calendar's owner draws it instead.
 */
@Composable
private fun DayCell(
    day: GridDay,
    isSelected: Boolean,
    isToday: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: (@Composable (day: GridDay, isSelected: Boolean, isToday: Boolean) -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .height(48.dp)
            .clickable(
                interactionSource = null,
                indication = ripple(bounded = false, radius = 20.dp),
                role = Role.Button,
                onClick = onClick,
            )
            .semantics {
                contentDescription = label
                selected = isSelected
            },
        contentAlignment = Alignment.Center,
    ) {
        if (content != null) {
            // The cell reads the date and its note; what is drawn would only repeat part of it.
            Box(Modifier.clearAndSetSemantics { }) { content(day, isSelected, isToday) }
            return@Box
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .then(
                    when {
                        isSelected -> Modifier.background(colors.primary, CircleShape)
                        isToday -> Modifier.border(1.dp, colors.primary, CircleShape)
                        else -> Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            // The cell reads the full date; the bare number would only repeat part of it.
            Text(
                text = day.date.day.toString(),
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    isSelected -> colors.onPrimary
                    isToday -> colors.primary
                    day.inMonth -> colors.onSurface
                    else -> colors.onSurfaceVariant
                },
                maxLines = 1,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
    }
}
