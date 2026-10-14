package com.rendyhd.vicu.ui.screens.today

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.components.shared.EmptyState
import com.rendyhd.vicu.ui.components.shared.LocalClockDay
import com.rendyhd.vicu.ui.components.shared.LocalDateFormat
import com.rendyhd.vicu.ui.components.shared.SmartListIdentity
import com.rendyhd.vicu.ui.theme.LocalVicuColors
import com.rendyhd.vicu.ui.theme.VicuMotion
import com.rendyhd.vicu.util.DateContext
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.DueDates

/**
 * Today with nothing left (card 4.11b). When the last task was just done ([justCleared]) the empty
 * state fades in over `fade.base` and the sun warms from quiet grey to its identity colour (the
 * icon only, never the text); when Today is empty from the start it is simply there in that colour.
 * Under it, the next upcoming task as a button to Upcoming. Every spec follows the system animator
 * scale, so with animations off the final state is shown at once.
 */
@Composable
internal fun TodayAllClear(
    justCleared: Boolean,
    nextUpcoming: Task?,
    onOpenUpcoming: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enter = remember { MutableTransitionState(!justCleared).apply { targetState = true } }
    val grey = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val identity = SmartListIdentity.TODAY.color(LocalVicuColors.current.identity)
    var warmed by remember { mutableStateOf(!justCleared) }
    LaunchedEffect(Unit) { warmed = true }
    val iconTint by animateColorAsState(
        targetValue = if (warmed) identity else grey,
        animationSpec = tween(VicuMotion.moveExpressiveMs, delayMillis = VicuMotion.fadeFastMs),
        label = "allClearSun",
    )
    AnimatedVisibility(
        visibleState = enter,
        modifier = modifier,
        enter = fadeIn(tween(VicuMotion.fadeBaseMs)),
    ) {
        EmptyState(
            icon = SmartListIdentity.TODAY.icon,
            title = "All clear",
            subtitle = "Nothing is due today",
            iconTint = iconTint,
            extra = nextUpcoming?.let { next -> { NextUpOffer(next, onOpenUpcoming) } },
        )
    }
}

/** "Next up", the title and the day, as one button to Upcoming (at least 48 dp tall). */
@Composable
private fun NextUpOffer(task: Task, onOpenUpcoming: () -> Unit) {
    val day = LocalClockDay.current
    val dateFormat = LocalDateFormat.current
    val date = DueDates.localDateOf(task.dueDate, day.zone)
    val dayLabel = date?.let { DateDisplay.formatDay(DateContext.HEADER_DAY, it, day.date, dateFormat) }
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = "Open Upcoming", role = Role.Button, onClick = onOpenUpcoming)
            .padding(horizontal = 12.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Next up",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = task.title,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 180.dp),
        )
        if (dayLabel != null) {
            Text(
                text = dayLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
