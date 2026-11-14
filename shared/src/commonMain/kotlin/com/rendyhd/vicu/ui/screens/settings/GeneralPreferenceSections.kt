package com.rendyhd.vicu.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.data.local.DrawerView
import com.rendyhd.vicu.ui.components.shared.SmartListIdentity
import com.rendyhd.vicu.ui.navigation.drawerViewChoices
import com.rendyhd.vicu.ui.screens.plan.PlannerOptions
import com.rendyhd.vicu.ui.theme.LocalVicuColors
import com.rendyhd.vicu.util.parser.SyntaxMode
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.rendyhd.vicu.util.TodayRoutines
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel

internal fun LazyListScope.bottomBarSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    openDialog: (SettingsDialog) -> Unit,
) {
    item(key = "bottombar_header") {
        Text(
            text = "Bottom Bar",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }

    item(key = "bottombar_desc") {
        Text(
            text = "Customize the last 3 slots. Inbox always stays first.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }

    state.bottomBarSlots.forEachIndexed { index, slot ->
        item(key = "bottombar_slot_$index") {
            BottomBarSlotRow(
                slot = slot,
                slotIndex = index,
                projects = state.projects,
                customLists = state.customLists,
                smartListColors = state.bottomBarSmartListColors,
                onClick = { openDialog(SettingsDialog.BottomBarSlot(index)) },
            )
        }
    }

    item(key = "bottombar_smart_list_colors") {
        SwitchRow(
            label = "Smart list colours",
            description = "Draw the Inbox, Today, Upcoming and Anytime icons in their own colours",
            checked = state.bottomBarSmartListColors,
            onCheckedChange = viewModel::setBottomBarSmartListColors,
        )
    }

    item(key = "bottombar_reset") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            TextButton(onClick = viewModel::resetBottomBar) {
                Text("Reset to Defaults")
            }
        }
    }

    item(key = "bottombar_divider") {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    }
}

/**
 * Which views the drawer lists above the projects: one switch for each it can list now (Today,
 * Upcoming and Anytime only while the bottom bar does not hold them, Routines, Review and Ask only
 * while they are on). A hidden view keeps working; the drawer just leaves it out.
 */
internal fun LazyListScope.drawerSection(
    state: SettingsUiState,
    askReady: Boolean,
    viewModel: SettingsViewModel,
) {
    item(key = "drawer_header") {
        Text(
            text = "Drawer",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }

    item(key = "drawer_desc") {
        Text(
            text = "Choose the views listed above your projects. Today, Upcoming and Anytime are in the drawer only when they are not in the bottom bar.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }

    val choices = drawerViewChoices(
        slots = state.bottomBarSlots,
        routinesEnabled = state.routineVisibility.enabled,
        reviewEnabled = state.reviewPrefs.enabled,
        askReady = askReady,
        plannerEnabled = state.behaviorPrefs.plannerEnabled,
    )
    choices.forEach { view ->
        item(key = "drawer_view_${view.name}") {
            DrawerViewRow(
                view = view,
                shown = view !in state.behaviorPrefs.hiddenDrawerViews,
                onShownChange = { viewModel.setDrawerViewShown(view, it) },
            )
        }
    }

    item(key = "drawer_divider") {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    }
}

/** The day planner, off by default and per device (contract 16.9), the same as the desktop's Settings, General, Plan. */
internal fun LazyListScope.plannerSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    item(key = "planner_header") {
        Text(
            text = "Plan",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }

    item(key = "planner_enabled") {
        SwitchRow(
            label = "Show Plan in the drawer",
            description = "A screen to give tasks a time on a timeline, and a Day view on Today. A time you set shows on every device, whether this is on or not.",
            checked = state.behaviorPrefs.plannerEnabled,
            onCheckedChange = viewModel::setPlannerEnabled,
        )
    }

    if (state.behaviorPrefs.plannerEnabled) {
        item(key = "planner_options") {
            PlannerOptions(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }

    item(key = "planner_divider") {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    }
}

/** A view of the drawer with its icon, as the drawer draws it, and a switch that lists it there. */
@Composable
private fun DrawerViewRow(
    view: DrawerView,
    shown: Boolean,
    onShownChange: (Boolean) -> Unit,
) {
    val identity = when (view) {
        DrawerView.TODAY -> SmartListIdentity.TODAY
        DrawerView.PLAN -> SmartListIdentity.PLAN
        DrawerView.UPCOMING -> SmartListIdentity.UPCOMING
        DrawerView.ANYTIME -> SmartListIdentity.ANYTIME
        DrawerView.LOGBOOK -> SmartListIdentity.LOGBOOK
        DrawerView.ROUTINES -> SmartListIdentity.ROUTINES
        DrawerView.REVIEW -> SmartListIdentity.REVIEW
        DrawerView.ASK -> null
    }
    val label = when (view) {
        DrawerView.TODAY -> "Today"
        DrawerView.PLAN -> "Plan"
        DrawerView.UPCOMING -> "Upcoming"
        DrawerView.ANYTIME -> "Anytime"
        DrawerView.LOGBOOK -> "Logbook"
        DrawerView.ROUTINES -> "Routines"
        DrawerView.REVIEW -> "Review"
        DrawerView.ASK -> "Ask"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = shown, role = Role.Switch, onValueChange = onShownChange)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Decorative: the label names the view.
        Icon(
            imageVector = identity?.icon ?: Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = identity?.color(LocalVicuColors.current.identity) ?: MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        // The row is the switch, so a screen reader finds one control.
        Switch(checked = shown, onCheckedChange = null)
    }
}

internal fun LazyListScope.widgetSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    item(key = "widget_header") {
        Text(
            text = "Widget",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
        )
    }

    item(key = "widget_smart_add") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Smart add button", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Add tasks to the widget's project or list target",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.widgetSmartAdd,
                onCheckedChange = viewModel::setWidgetSmartAdd,
            )
        }
    }

    item(key = "widget_context_nav") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Context navigation", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Tapping the widget title opens the matching screen",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.widgetContextNav,
                onCheckedChange = viewModel::setWidgetContextNav,
            )
        }
    }

    if (state.hasWidgetVoiceButton) {
        item(key = "widget_voice_button") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Voice button", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Show the microphone next to + to add a task by voice",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.widgetVoiceButton,
                    onCheckedChange = viewModel::setWidgetVoiceButton,
                )
            }
        }
    }

    item(key = "widget_divider") {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    }
}

internal fun LazyListScope.routinesSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    item(key = "routines_header") {
        Text(
            text = "Routines",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
        )
    }
    item(key = "routines_enabled") {
        SwitchRow(
            label = "Enable routines",
            description = "Off hides routines and stops their reminders; nothing is deleted",
            checked = state.routineVisibility.enabled,
            onCheckedChange = viewModel::setRoutinesEnabled,
        )
    }
    if (state.routineVisibility.enabled) {
        item(key = "routines_in_today") {
            SwitchRow(
                label = "Show routines in Today",
                description = "Lists the routines still open today; finished ones are left out",
                checked = state.routineVisibility.showInToday,
                onCheckedChange = viewModel::setRoutinesShowInToday,
            )
        }
        if (state.routineVisibility.showInToday) {
            item(key = "routines_today_limit") { RoutinesTodayLimitRow(state.routineVisibility.todayLimit, viewModel::setRoutinesTodayLimit) }
        }
        item(key = "routines_hidden") { HiddenSharedRoutines() }
    }
    item(key = "routines_divider") {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    }
}

internal fun LazyListScope.logbookSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    openDialog: (SettingsDialog) -> Unit,
) {
    item(key = "logbook_header") {
        Text(
            text = "Logbook",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
        )
    }
    item(key = "logbook_retention_enabled") {
        SwitchRow(
            label = "Hide old completed tasks",
            description = "Older completed tasks are hidden from Logbook. Nothing is deleted.",
            checked = state.logbookPrefs.enabled,
            onCheckedChange = viewModel::setLogbookRetentionEnabled,
        )
    }
    if (state.logbookPrefs.enabled) {
        item(key = "logbook_retention_days") {
            SettingsValueRow(
                label = "Keep completed tasks for",
                value = "${state.logbookPrefs.retentionDays} days",
                onClick = { openDialog(SettingsDialog.LogbookRetention) },
            )
        }
    }
    item(key = "logbook_divider") {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    }
}

internal fun LazyListScope.inputParsingSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    item(key = "nlp_header") {
        Text(
            text = "Input Parsing",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
        )
    }

    item(key = "nlp_enabled") {
        SwitchRow(
            label = "Natural Language Parsing",
            description = "Extract dates, labels, projects, and priority from task titles",
            checked = state.nlpConfig.enabled,
            onCheckedChange = viewModel::setNlpEnabled,
        )
    }

    if (state.nlpConfig.enabled) {
        item(key = "nlp_syntax_mode") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "Syntax Mode",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(modifier = Modifier.height(8.dp))
                @OptIn(ExperimentalMaterial3Api::class)
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val options = listOf(
                        SyntaxMode.TODOIST to "Todoist",
                        SyntaxMode.VIKUNJA to "Vikunja",
                    )
                    options.forEachIndexed { index, (mode, label) ->
                        SegmentedButton(
                            selected = state.nlpConfig.syntaxMode == mode,
                            onClick = { viewModel.setNlpSyntaxMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                        ) {
                            Text(label)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                val hint = if (state.nlpConfig.syntaxMode == SyntaxMode.TODOIST) {
                    "#project  @label  p1-p4"
                } else {
                    "+project  *label  !1-!4"
                }
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    item(key = "nlp_bang_today") {
        SwitchRow(
            label = "! sets due date to today",
            description = "Add ! to set the due date to today.",
            checked = state.nlpConfig.bangToday,
            onCheckedChange = viewModel::setBangToday,
        )
    }

    item(key = "nlp_divider") {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    }
}

internal fun LazyListScope.behaviorSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onPickCompletionSound: () -> Unit,
) {
    item(key = "behavior_header") {
        SectionHeader(icon = Icons.Outlined.Tune, title = "Preferences")
    }

    item(key = "confirm_before_delete") {
        SwitchRow(
            label = "Confirm before deleting",
            description = "Show a confirmation dialog before deleting tasks, projects, labels and lists",
            checked = state.behaviorPrefs.confirmBeforeDelete,
            onCheckedChange = viewModel::setConfirmBeforeDelete,
        )
    }

    item(key = "subtask_display_mode") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(text = "Subtasks in task lists", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "Keep lists minimal or expand subtasks beneath their parent",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            @OptIn(ExperimentalMaterial3Api::class)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val options = listOf(
                    com.rendyhd.vicu.data.local.SubtaskDisplayMode.INSIDE_TASK to "Inside task",
                    com.rendyhd.vicu.data.local.SubtaskDisplayMode.EXPANDABLE to "Expandable",
                )
                options.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = state.behaviorPrefs.subtaskDisplayMode == mode,
                        onClick = { viewModel.setSubtaskDisplayMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    ) {
                        Text(label)
                    }
                }
            }
        }
    }

    item(key = "keep_entry_open") {
        SwitchRow(
            label = "Keep add-task open",
            description = "Stay in the new-task sheet after saving, to add several quickly",
            checked = state.behaviorPrefs.keepEntryOpen,
            onCheckedChange = viewModel::setKeepEntryOpen,
        )
    }

    item(key = "fab_align_start") {
        SwitchRow(
            label = "Left-handed add button",
            description = "Move the + button to the bottom-left corner",
            checked = state.behaviorPrefs.fabAlignStart,
            onCheckedChange = viewModel::setFabAlignStart,
        )
    }

    item(key = "completion_sound_enabled") {
        SwitchRow(
            label = "Play sound when completing a task",
            description = "Plays the system notification sound. Tap below for a custom sound.",
            checked = state.behaviorPrefs.completionSoundEnabled,
            onCheckedChange = viewModel::setCompletionSoundEnabled,
        )
    }

    if (state.behaviorPrefs.completionSoundEnabled) {
        item(key = "completion_sound_custom") {
            val hasCustom = !state.behaviorPrefs.completionSoundUri.isNullOrBlank()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onPickCompletionSound)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (hasCustom) "Custom sound selected" else "Pick a custom sound",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = if (hasCustom) "Tap to change" else "Default: system notification sound",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = viewModel::playCompletionSound) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = "Play completion sound")
                }
                if (hasCustom) {
                    TextButton(onClick = { viewModel.setCompletionSoundUri(null) }) {
                        Text("Reset")
                    }
                }
            }
        }
    }

    item(key = "behavior_divider") {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    }
}

/** The routines others shared with this account that it hid, each with Show (cross-app semantics 6.9). */
@Composable
private fun HiddenSharedRoutines(viewModel: HiddenRoutinesViewModel = koinViewModel()) {
    val hidden by viewModel.hidden.collectAsStateWithLifecycle()
    if (hidden.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Hidden shared routines", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Routines someone shared with you that you hid. They stay shared; only you do not see them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        hidden.forEach { routine ->
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(routine.definition.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(
                    onClick = { viewModel.show(routine) },
                    modifier = Modifier.semantics { contentDescription = "Show ${routine.definition.name}" },
                ) { Text("Show") }
            }
        }
    }
}

/** How many routines due now Today shows before "Show more" (cross-app semantics 6.10). */
@Composable
private fun RoutinesTodayLimitRow(limit: Int, onPick: (Int) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val label = { value: Int -> if (value == TodayRoutines.ALL) "All" else value.toString() }
    SettingsValueRow("Routines shown on Today", label(limit), onClick = { open = true })
    if (open) {
        ChoiceDialog(
            title = "Routines shown on Today",
            options = TodayRoutines.LIMIT_CHOICES.map { it.toString() to label(it) },
            selected = limit.toString(),
            onPick = { picked ->
                onPick(picked.toInt())
                open = false
            },
            onDismiss = { open = false },
        )
    }
}
