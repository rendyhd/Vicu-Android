package com.rendyhd.vicu.ui.screens.routines

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.rendyhd.vicu.domain.model.OccurrenceStatus
import com.rendyhd.vicu.domain.model.Routine
import com.rendyhd.vicu.domain.model.RoutineOccurrenceRecord
import com.rendyhd.vicu.ui.components.collab.LocalCollab
import com.rendyhd.vicu.ui.components.picker.MonthCalendar
import com.rendyhd.vicu.ui.components.shared.LocalClockDay
import com.rendyhd.vicu.ui.components.shared.LocalDateFormat
import com.rendyhd.vicu.ui.theme.contrastRatio
import com.rendyhd.vicu.util.DateContext
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.GridDay
import com.rendyhd.vicu.util.RoutineTrend
import com.rendyhd.vicu.util.RoutineTrend.DayState
import kotlinx.datetime.LocalDate

/**
 * A routine's history (docs/cross-app-semantics-v1.md, section 6.11): the summary line, a month
 * with a mark on each day, what the day picked holds, and for a schedule twelve weeks of bars. The
 * rules are [RoutineTrend]; the desktop's History dialog draws the same.
 */
@Composable
internal fun RoutineHistoryDialog(
    routine: Routine,
    history: List<RoutineOccurrenceRecord>,
    onDismiss: () -> Unit,
) {
    val clock = LocalClockDay.current
    val today = clock.date
    val trend = remember(routine.definition, history, clock) {
        RoutineTrend.of(routine.definition, history, today, clock.zone, clock.weekStart)
    }
    var picked by remember { mutableStateOf(today) }
    val tint = routineTint(routine.definition)
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
            Column(modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)) {
                Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                        Text(routine.definition.name, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            trend.summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    MonthCalendar(
                        selected = picked,
                        onSelect = { picked = it },
                        modifier = Modifier.padding(horizontal = 8.dp),
                        dayNote = { date -> dayStateWords(trend.state(date)) },
                    ) { day, isSelected, isToday ->
                        TrendDay(day, trend.state(day.date), isSelected, isToday, tint)
                    }
                    PickedDay(routine, trend, picked, today, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                    trend.weeks?.let { weeks ->
                        WeekBars(weeks, tint, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(end = 12.dp)) { Text("Done") }
            }
        }
    }
}

/** What a screen reader says after the date; null for a day that holds nothing. */
private fun dayStateWords(state: DayState): String? = when (state) {
    DayState.DONE -> "done"
    DayState.PART -> "partly done"
    DayState.SKIPPED -> "skipped"
    DayState.MISSED -> "not logged"
    DayState.OPEN -> "to do"
    DayState.NONE -> null
}

/**
 * A day of the month with its mark, in the routine's colour: filled when done, a pale fill when
 * partly done, a ring when skipped, a small neutral dot when missed. Today is outlined and the day
 * picked stands on a soft ground; a day of another month is dimmed.
 */
@Composable
private fun TrendDay(day: GridDay, state: DayState, isSelected: Boolean, isToday: Boolean, tint: Color) {
    val scheme = MaterialTheme.colorScheme
    val dim = if (day.inMonth) 1f else 0.4f
    val fill = tint.copy(alpha = dim)
    val pale = tint.copy(alpha = 0.26f * dim)
    val missed = scheme.onSurfaceVariant.copy(alpha = dim)
    val outline = scheme.onSurface
    // The number on a filled day: whichever of the page's two tones reads better on the colour.
    val onFill = if (contrastRatio(scheme.surface, tint) >= contrastRatio(scheme.onSurface, tint)) scheme.surface else scheme.onSurface
    Box(
        modifier = Modifier
            .size(40.dp)
            .then(if (isSelected) Modifier.background(scheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(12.dp)) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .drawBehind {
                    val radius = size.minDimension / 2
                    when (state) {
                        DayState.DONE -> drawCircle(fill, radius)
                        DayState.PART -> drawCircle(pale, radius)
                        DayState.SKIPPED -> {
                            val stroke = 1.5.dp.toPx()
                            drawCircle(fill, radius - stroke / 2, style = Stroke(stroke))
                        }
                        DayState.MISSED -> drawCircle(missed, 2.dp.toPx(), Offset(size.width / 2, size.height - 2.dp.toPx()))
                        DayState.OPEN, DayState.NONE -> {}
                    }
                    if (isToday) drawCircle(outline, radius + 3.dp.toPx(), style = Stroke(1.dp.toPx()))
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = day.date.day.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = when (state) {
                    DayState.DONE -> onFill.copy(alpha = dim)
                    DayState.NONE -> scheme.onSurfaceVariant.copy(alpha = 0.7f * dim)
                    else -> scheme.onSurface.copy(alpha = dim)
                },
                maxLines = 1,
            )
        }
    }
}

/** The day picked: its date and each time of the routine that day with what became of it. */
@Composable
private fun PickedDay(routine: Routine, trend: RoutineTrend, date: LocalDate, today: LocalDate, modifier: Modifier = Modifier) {
    val format = LocalDateFormat.current
    val collab = LocalCollab.current
    val occurrences = trend.occurrencesOn(date)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            DateDisplay.formatDay(DateContext.CHIP, date, today, format),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        if (occurrences.isEmpty()) {
            Text(
                if (date > today) "Still to come" else "Nothing scheduled",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (occurrence in occurrences) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    timeText(occurrence.minutes, format),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    // "Done by Maya" on a shared routine, else the plain status.
                    occurrence.record?.let { RoutineSharing.loggedText(routine, it.status, it.loggedByUserId, collab.state) }
                        ?: when (occurrence.status) {
                            OccurrenceStatus.COMPLETED -> "Done"
                            OccurrenceStatus.SKIPPED -> "Skipped"
                            OccurrenceStatus.NOT_LOGGED -> "Not logged"
                            OccurrenceStatus.PENDING -> "To do"
                        },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** Twelve weeks, oldest first: each bar as high as the share of that week's times that were done. */
@Composable
private fun WeekBars(weeks: List<RoutineTrend.Tally>, tint: Color, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val last = weeks.lastOrNull()
    val spoken = "Last ${weeks.size} weeks" + if (last != null && last.expected > 0) ". This week ${last.done} of ${last.expected}" else ""
    Column(modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = spoken }) {
        Row(
            modifier = Modifier.fillMaxWidth().height(32.dp).clearAndSetSemantics { },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            for (week in weeks) {
                val share = if (week.expected > 0) week.done.toFloat() / week.expected else 0f
                Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                    // The line every bar stands on, so a week with nothing done still has its place.
                    Box(Modifier.fillMaxWidth().height(2.dp).background(scheme.outlineVariant, RoundedCornerShape(1.dp)))
                    if (share > 0f) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(share.coerceIn(0.08f, 1f))
                                .background(tint, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)),
                        )
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clearAndSetSemantics { }) {
            Text(
                "${weeks.size} weeks ago",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text("This week", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
    }
}
