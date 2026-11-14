package com.rendyhd.vicu.ui.screens.guided

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.ai.organize.Suggestion
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.ui.components.assistant.dashedOutline
import com.rendyhd.vicu.ui.components.picker.ProjectPickerDialog
import com.rendyhd.vicu.ui.components.picker.WhenSheet
import com.rendyhd.vicu.ui.components.shared.LocalClockDay
import com.rendyhd.vicu.ui.components.shared.LocalDateFormat
import com.rendyhd.vicu.util.DateContext
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.util.WhenLogic
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.atTime
import kotlinx.datetime.toLocalDateTime

/** A small heading over a group of choices on a card. */
@Composable
internal fun ChoiceHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

/**
 * The When choices of a card (docs/cross-app-semantics-v1.md, section 14.3): the When sheet's quick
 * choices, a picked date (the When sheet) and No date while there is one. [due] is the card's
 * choice (null: unchanged), [currentDue] the task's stored due date; [suggested] draws the dashed
 * outline around the choice a suggestion made. On a card a second tap takes a choice back; with
 * [toggle] false (a review page, which writes at once) every tap is a choice.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WhenChoices(
    due: DueChoice?,
    currentDue: String,
    suggested: Boolean,
    onChoose: (DueChoice?) -> Unit,
    toggle: Boolean = true,
) {
    val day = LocalClockDay.current
    val format = LocalDateFormat.current
    var picking by remember { mutableStateOf(false) }
    val outline = MaterialTheme.colorScheme.primary
    val chosen = due as? DueChoice.On
    val quick = WhenLogic.quickChoices(day.date, day.weekStart)
    val chosenIsQuick = chosen != null && !chosen.hasTime && quick.any { it.date == chosen.local.date }
    val hasDue = DateUtils.parseIsoDate(currentDue) != null
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (choice in quick) {
            val selected = chosen != null && !chosen.hasTime && chosen.local.date == choice.date
            FilterChip(
                selected = selected,
                onClick = { onChoose(if (selected && toggle) null else DueChoice.On(choice.date.atTime(DueDates.DATE_ONLY_TIME), false)) },
                label = { Text(choice.label) },
                modifier = Modifier.dashedOutline(outline, suggested && selected),
            )
        }
        if (chosen != null && !chosenIsQuick) {
            FilterChip(
                selected = true,
                onClick = { onChoose(if (toggle) null else chosen) },
                label = { Text(DateDisplay.format(DateContext.CHIP, chosen.local, day.date, !chosen.hasTime, format)) },
                leadingIcon = { Icon(Icons.Outlined.Event, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.dashedOutline(outline, suggested),
            )
        }
        FilterChip(
            selected = false,
            onClick = { picking = true },
            label = { Text("Pick a date") },
            leadingIcon = { Icon(Icons.Outlined.Event, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        if (hasDue) {
            val clearing = due == DueChoice.Clear
            FilterChip(
                selected = clearing,
                onClick = { onChoose(if (clearing && toggle) null else DueChoice.Clear) },
                label = { Text("No date") },
                modifier = Modifier.dashedOutline(outline, suggested && clearing),
            )
        }
    }
    if (picking) {
        val start = chosen?.let { DueDates.fromParsed(it.local, it.hasTime, day.zone).toString() } ?: currentDue.takeIf { hasDue }
        WhenSheet(
            currentDate = start,
            onDateSelected = { iso ->
                val instant = DateUtils.parseIsoDate(iso)
                if (instant != null) {
                    val local = instant.toLocalDateTime(day.zone)
                    onChoose(DueChoice.On(LocalDateTime(local.date, local.time), !DueDates.isDateOnly(instant, day.zone)))
                }
            },
            onClearDate = { onChoose(DueChoice.Clear) },
            onDismiss = { picking = false },
        )
    }
}

/** The project a card puts the task in: the chosen one, or where it is; tapping opens the picker. */
@Composable
internal fun ProjectChoice(
    path: String,
    selectedProjectId: Long,
    changed: Boolean,
    suggested: Boolean,
    projects: List<Project>,
    inboxId: Long?,
    onChoose: (Long?) -> Unit,
    currentProjectId: Long,
) {
    var picking by remember { mutableStateOf(false) }
    FilterChip(
        selected = changed,
        onClick = { picking = true },
        label = { Text(path.ifBlank { "Choose a project" }) },
        leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(18.dp)) },
        modifier = Modifier.dashedOutline(MaterialTheme.colorScheme.primary, suggested),
    )
    if (picking) {
        ProjectPickerDialog(
            projects = projects,
            selectedProjectId = selectedProjectId,
            onProjectSelected = { id ->
                onChoose(id.takeIf { it != currentProjectId })
                picking = false
            },
            onDismiss = { picking = false },
            inboxProjectId = inboxId ?: 0L,
        )
    }
}

/** The model's reason for a card's suggestion, or that it is on its way (ai-assistant 9.4). */
@Composable
internal fun SuggestionLine(suggestion: Suggestion?, waiting: Boolean) {
    val text = when {
        suggestion != null -> when (suggestion.close) {
            "done" -> "Looks done already" + suggestion.reason.let { if (it.isBlank()) "" else ": $it" }
            "delete" -> "Looks like it can go" + suggestion.reason.let { if (it.isBlank()) "" else ": $it" }
            else -> if (suggestion.leavesIt) "Fine as it is: ${suggestion.reason}" else suggestion.reason
        }
        waiting -> "Getting a suggestion"
        else -> return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        if (suggestion == null) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = "Suggestion", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** The end of a session: what it changed, Undo all while there is something to undo, and Done. */
@Composable
internal fun FinishedPane(title: String, summary: String, canUndo: Boolean, busy: Boolean, onUndo: () -> Unit, onDone: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp), textAlign = TextAlign.Center)
        Text(
            summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 24.dp)) {
            if (canUndo) OutlinedButton(onClick = onUndo, enabled = !busy) { Text("Undo all") }
            Button(onClick = onDone) { Text("Done") }
        }
        Spacer(Modifier.fillMaxWidth())
    }
}
