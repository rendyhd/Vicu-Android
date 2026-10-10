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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import com.rendyhd.vicu.domain.model.BottomBarSlot
import com.rendyhd.vicu.util.parser.SyntaxMode

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
                onClick = { openDialog(SettingsDialog.BottomBarSlot(index)) },
            )
        }
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

    item(key = "widget_divider") {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    }
}

internal fun LazyListScope.reviewSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    openDialog: (SettingsDialog) -> Unit,
) {
    item(key = "review_header") {
        Text(
            text = "Review",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
        )
    }
    item(key = "review_enabled") {
        SwitchRow(
            label = "Enable project review tracking",
            description = "Periodically review your projects",
            checked = state.reviewPrefs.enabled,
            onCheckedChange = viewModel::setReviewEnabled,
        )
    }
    if (state.reviewPrefs.enabled) {
        item(key = "review_cadence") {
            SettingsValueRow(
                label = "Default review cadence",
                value = "${state.reviewPrefs.defaultCadenceDays} days",
                onClick = { openDialog(SettingsDialog.ReviewCadence) },
            )
        }
        item(key = "review_exclude_inbox") {
            SwitchRow(
                label = "Exclude Inbox from review",
                description = "Don't track the inbox project",
                checked = state.reviewPrefs.excludeInbox,
                onCheckedChange = viewModel::setReviewExcludeInbox,
            )
        }
    }
    item(key = "review_divider") {
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
    }
    item(key = "routines_divider") {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    }
}

internal fun LazyListScope.inboxSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    item(key = "inbox_header") {
        Text(
            text = "Inbox",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
        )
    }
    item(key = "inbox_exclude_dated") {
        SwitchRow(
            label = "Move dated tasks out of Inbox",
            description = "Tasks with a due date no longer appear in Inbox.",
            checked = state.behaviorPrefs.inboxExcludeDated,
            onCheckedChange = viewModel::setInboxExcludeDated,
        )
    }
    item(key = "inbox_divider") {
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

    item(key = "subproject_display_mode") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(text = "Subprojects in project views", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "Combine child tasks into sections or open each child as its own project",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            @OptIn(ExperimentalMaterial3Api::class)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val options = listOf(
                    com.rendyhd.vicu.data.local.SubprojectDisplayMode.SECTIONS to "Sections",
                    com.rendyhd.vicu.data.local.SubprojectDisplayMode.PROJECT_ROWS to "Project rows",
                )
                options.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = state.behaviorPrefs.subprojectDisplayMode == mode,
                        onClick = { viewModel.setSubprojectDisplayMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    ) {
                        Text(label)
                    }
                }
            }
        }
    }

    item(key = "show_project_progress") {
        SwitchRow(
            label = "Show project progress",
            description = "Progress rings next to the projects in the drawer",
            checked = state.behaviorPrefs.showProjectProgress,
            onCheckedChange = viewModel::setShowProjectProgress,
        )
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

    item(key = "schedule_action") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(text = "Swipe to schedule", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "What swiping a task does",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            @OptIn(ExperimentalMaterial3Api::class)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val options = listOf(
                    com.rendyhd.vicu.data.local.ScheduleAction.DUE_TODAY to "Choose when",
                    com.rendyhd.vicu.data.local.ScheduleAction.PRIORITY_URGENT to "Urgent",
                )
                options.forEachIndexed { index, (action, label) ->
                    SegmentedButton(
                        selected = state.behaviorPrefs.scheduleAction == action,
                        onClick = { viewModel.setScheduleAction(action) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    ) {
                        Text(label)
                    }
                }
            }
        }
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
