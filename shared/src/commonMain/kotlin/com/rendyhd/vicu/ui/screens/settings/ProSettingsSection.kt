package com.rendyhd.vicu.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rendyhd.vicu.data.local.BehaviorPrefs
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.ui.components.pro.LocalPro
import com.rendyhd.vicu.ui.screens.shared.ProjectActions
import com.rendyhd.vicu.util.ProjectCategoryState
import com.rendyhd.vicu.util.pro.ProLayer
import com.rendyhd.vicu.util.pro.ProSettings
import com.rendyhd.vicu.util.pro.StartDates
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Settings, General, Pro (contract 21.1), the desktop's Settings, Pro: the switch for this device,
 * the layers a phone uses, the assistant switch, the first-run note about start dates set elsewhere
 * and the work area. Turning Pro off writes nothing but this device's switch (O2).
 */
internal fun LazyListScope.proSection() {
    item(key = "pro_header") {
        Text(
            text = "Pro",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
    item(key = "pro_settings") { ProSettingsSection() }
    // No divider: Plan follows as part of the same group (GeneralTab).
}

/** A row that is a checkbox: its name and a note under it. */
@Composable
private fun CheckRow(label: String, note: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (note != null) {
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun ProSettingsSection() {
    val store = koinInject<BehaviorPrefsStore>()
    val scope = rememberCoroutineScope()
    val behavior by store.getPrefs().collectAsStateWithLifecycle(BehaviorPrefs())
    Column {
        Note(
            "Extra tools for heavier work: what you can do now, and promises to and from people. Pro only adds. " +
                "Turn it off and Vicu is as it was; nothing is removed. This choice is for this device.",
        )
        SwitchRow(
            label = "Turn on Pro on this device",
            description = "Start dates, on hold, after, sequential projects, flags, people and promises",
            checked = behavior.proEnabled,
            onCheckedChange = { on -> scope.launch { store.setProEnabled(on) } },
        )
        if (!behavior.proEnabled) return@Column
        CheckRow(
            label = "Know what you can do now",
            note = "Start dates, on hold, after, sequential projects, flags and estimates.",
            checked = behavior.proLayers.availability,
        ) { on -> scope.launch { store.setProLayer(ProLayer.AVAILABILITY, on) } }
        CheckRow(
            label = "Promises and people",
            note = "People, what you promised and what they promised, verdicts and recaps.",
            checked = behavior.proLayers.promises,
        ) { on -> scope.launch { store.setProLayer(ProLayer.PROMISES, on) } }
        Note("The portfolio, meetings and import are on the desktop app.")
        CheckRow(
            label = "Assistant help in Pro",
            note = "Pro data reaches the assistant only while this is on, on the model set in Assistant. " +
                "Work text goes to that provider; check that your employer allows it.",
            checked = behavior.proAssistant,
        ) { on -> scope.launch { store.setProAssistant(on) } }
        if (!behavior.proOnboardingSeen) StartDateNotice(onSeen = { scope.launch { store.setProOnboardingSeen(true) } })
        if (behavior.isProEnabled(ProLayer.PROMISES)) WorkArea()
    }
}

/** The first-run note (contract 21.3): tasks that already have a start date from Vikunja's web app. */
@Composable
private fun StartDateNotice(onSeen: () -> Unit) {
    val world = LocalPro.current.world
    if (!world.on) return
    val starting = remember(world) { world.open.filter { StartDates.startDateOf(it, world.zone) != null } }
    var showing by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text("Start dates", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp))
        Note(
            when (starting.size) {
                0 -> "With Pro, a task with a start date waits until that day. None of your open tasks has one yet."
                1 -> "With Pro, a task with a start date waits until that day. 1 open task has one already, set in Vikunja's web app or another device."
                else -> "With Pro, a task with a start date waits until that day. ${starting.size} open tasks have one already, set in Vikunja's web app or another device."
            },
        )
        if (showing) {
            starting.take(50).forEach { task ->
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
                )
            }
        }
        Row(modifier = Modifier.padding(horizontal = 8.dp)) {
            if (starting.isNotEmpty() && !showing) TextButton(onClick = { showing = true }) { Text("Show them") }
            TextButton(onClick = onSeen) { Text("Got it") }
        }
    }
}

/** The work area (contract 21.1): the categories promises apply in; none ticked is every project. Synced. */
@Composable
private fun WorkArea() {
    val pro = LocalPro.current
    val categories by koinInject<ProjectActions>().categories.collectAsStateWithLifecycle(ProjectCategoryState.EMPTY)
    val chosen = remember(pro.world) { ProSettings.readArea(pro.world.prefs[ProSettings.AREA_KEY]).toSet() }
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text("Work area", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp))
        Note(
            "Promises and people apply to projects in these categories. With none ticked they apply to every project. " +
                "Shared with your other devices.",
        )
        if (categories.categories.isEmpty()) {
            Note("You have no project categories. Add one in Settings, Projects to limit Pro to it.")
        } else {
            categories.categories.forEach { category ->
                CheckRow(label = category.name, note = null, checked = category.id in chosen) { on ->
                    val next = if (on) chosen + category.id else chosen - category.id
                    pro.actions?.setArea(categories.categories.map { it.id }.filter { it in next })
                }
            }
        }
    }
}
