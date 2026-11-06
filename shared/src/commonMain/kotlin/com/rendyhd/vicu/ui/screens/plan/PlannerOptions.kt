package com.rendyhd.vicu.ui.screens.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rendyhd.vicu.data.local.BehaviorPrefs
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.RoutinePrefsStore
import com.rendyhd.vicu.data.repository.PlannerPreferences
import com.rendyhd.vicu.permission.NotificationPermissionCoordinator
import com.rendyhd.vicu.ui.components.shared.LocalDateFormat
import com.rendyhd.vicu.ui.screens.settings.SummaryTimeDialog
import com.rendyhd.vicu.util.PlannerHours
import com.rendyhd.vicu.util.PlannerNudge
import com.rendyhd.vicu.util.PlannerTimeline
import com.rendyhd.vicu.util.TimeBlocks
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** The planner's settings, shared by the Plan screen's gear and Settings, as on the desktop. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlannerOptions(modifier: Modifier = Modifier) {
    val preferences = koinInject<PlannerPreferences>()
    val behaviorStore = koinInject<BehaviorPrefsStore>()
    val routineStore = koinInject<RoutinePrefsStore>()
    val permission = koinInject<NotificationPermissionCoordinator>()
    val scope = rememberCoroutineScope()
    val defaultLength by preferences.defaultLength.collectAsStateWithLifecycle(TimeBlocks.DEFAULT_LENGTH_MINUTES)
    val hours by preferences.hours.collectAsStateWithLifecycle(TimeBlocks.DEFAULT_HOURS)
    val behavior by behaviorStore.getPrefs().collectAsStateWithLifecycle(BehaviorPrefs())
    val routinesOn by routineStore.enabled.collectAsStateWithLifecycle(true)
    var editing by remember { mutableStateOf<HoursEnd?>(null) }
    val format = LocalDateFormat.current

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column {
            Text("Default length", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            FlowRow(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TimeBlocks.BLOCK_LENGTHS.forEach { minutes ->
                    FilterChip(
                        selected = defaultLength == minutes,
                        onClick = { scope.launch { preferences.setDefaultLength(minutes) } },
                        label = { Text(PlannerTimeline.formatLength(minutes)) },
                    )
                }
            }
            Text(
                text = "What a task takes when you drop it on a time. Synced to your other devices.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column {
            Text("Work hours", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Row(
                modifier = Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { editing = HoursEnd.START },
                    modifier = Modifier.semantics { contentDescription = "Work hours start, ${hours.start}" },
                ) { Text(clockLabel(hours.start, format)) }
                Text("to", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(
                    onClick = { editing = HoursEnd.END },
                    modifier = Modifier.semantics { contentDescription = "Work hours end, ${hours.end}" },
                ) { Text(clockLabel(hours.end, format)) }
            }
            Text(
                text = "Used for free time, Fit and Plan my day. Synced to your other devices.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(
                        value = behavior.plannerNudge != null,
                        role = Role.Switch,
                        onValueChange = { on ->
                            scope.launch { behaviorStore.setPlannerNudge(if (on) PlannerNudge.DEFAULT_TIME else null) }
                            if (on) permission.onFeatureEnabled()
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Ask me to plan tomorrow", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = behavior.plannerNudge != null, onCheckedChange = null)
            }
            behavior.plannerNudge?.let { time ->
                OutlinedButton(
                    onClick = { editing = HoursEnd.NUDGE },
                    modifier = Modifier.semantics { contentDescription = "Ask at $time" },
                ) { Text(clockLabel(time, format)) }
            }
            Text(
                text = "One notification each evening on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (routinesOn) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(
                        value = behavior.plannerShowRoutines,
                        role = Role.Switch,
                        onValueChange = { shown -> scope.launch { behaviorStore.setPlannerShowRoutines(shown) } },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Show routines on the timeline", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = behavior.plannerShowRoutines, onCheckedChange = null)
            }
        }
    }

    editing?.let { end ->
        val current = when (end) {
            HoursEnd.START -> hours.start
            HoursEnd.END -> hours.end
            HoursEnd.NUDGE -> behavior.plannerNudge ?: PlannerNudge.DEFAULT_TIME
        }
        val minutes = TimeBlocks.clockMinutes(current)
        SummaryTimeDialog(
            title = when (end) {
                HoursEnd.START -> "Work hours start"
                HoursEnd.END -> "Work hours end"
                HoursEnd.NUDGE -> "Ask at"
            },
            initialHour = minutes / 60,
            initialMinute = minutes % 60,
            onConfirm = { hour, minute ->
                val text = TimeBlocks.clockText(hour * 60 + minute)
                scope.launch {
                    when (end) {
                        // A start after the end is not saved and the hours stay what they were, as on the desktop.
                        HoursEnd.START -> preferences.setHours(PlannerHours(text, hours.end))
                        HoursEnd.END -> preferences.setHours(PlannerHours(hours.start, text))
                        HoursEnd.NUDGE -> behaviorStore.setPlannerNudge(text)
                    }
                }
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

private enum class HoursEnd { START, END, NUDGE }

