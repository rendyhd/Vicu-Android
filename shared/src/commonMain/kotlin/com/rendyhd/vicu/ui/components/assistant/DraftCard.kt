package com.rendyhd.vicu.ui.components.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.ai.AiContract
import com.rendyhd.vicu.ai.dictation.CheckedDraft
import com.rendyhd.vicu.ai.dictation.CheckedProject
import com.rendyhd.vicu.ai.dictation.DictationChecks
import com.rendyhd.vicu.ai.dictation.DictationContext
import com.rendyhd.vicu.ai.dictation.DraftLabel
import com.rendyhd.vicu.ai.dictation.DroppedField
import com.rendyhd.vicu.ui.components.shared.LocalClockDay
import com.rendyhd.vicu.ui.components.shared.LocalDateFormat
import com.rendyhd.vicu.util.DateContext
import com.rendyhd.vicu.util.DateDisplay
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atTime
import kotlinx.datetime.plus

/** A dashed outline: a field Vicu or the model was not sure of (section 4.3). */
fun Modifier.dashedOutline(color: Color, active: Boolean): Modifier =
    if (!active) {
        this
    } else {
        drawBehind {
            drawRoundRect(
                color = color,
                cornerRadius = CornerRadius(8.dp.toPx()),
                style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
            )
        }
    }

/** Why a field was left empty, in words for the card; [brainDump] for a brain dump's limits (section 4.8.3). */
fun droppedText(d: DroppedField, brainDump: Boolean = false): String = when (d.field) {
    "when" -> "Did not understand the date “${d.value.toString().trim('"')}”"
    "priority" -> "Left out the priority"
    "repeat" -> "Left out the repeat"
    "reminder" -> "Left out the reminder"
    "place" -> "No saved place matches"
    "title" -> "Skipped a task without a title"
    "task" -> "More than ${if (brainDump) AiContract.limits.brainDumpTasksMax else AiContract.limits.draftsMax} tasks: the rest were left out"
    "project" -> "More than ${AiContract.limits.brainDumpProjectsMax} new projects: the rest were left out"
    "project_title" -> "Skipped a new project without a name"
    "parent" -> "Its parent project was not found, so it goes at the top level"
    else -> "Left out ${d.field}"
}

/**
 * One draft task on a card: its title (editable), notes, and chips for the date, project, labels,
 * priority, repeat, reminder, place and subtasks. [editable] lets the chips change it by hand.
 * [newProjects] are a brain dump's new projects, which the project chip offers too.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DraftCard(
    draft: CheckedDraft,
    context: DictationContext?,
    dropped: List<DroppedField>,
    changed: Set<String>,
    editable: Boolean,
    onChange: (CheckedDraft) -> Unit,
    onRemove: (() -> Unit)?,
    modifier: Modifier = Modifier,
    newProjects: List<CheckedProject> = emptyList(),
    brainDump: Boolean = false,
    /** A card that cannot change but whose date can (Ask's cards, section 5.4): opens the When sheet. */
    onDueClick: (() -> Unit)? = null,
) {
    val outline = MaterialTheme.colorScheme.tertiary
    val highlight = MaterialTheme.colorScheme.secondaryContainer
    val guessed = draft.guessed.toSet()
    OutlinedCard(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.weight(1f)
                        .dashedOutline(outline, "title" in guessed)
                        .let { if ("title" in changed || "new" in changed) it.padding(2.dp) else it },
                ) {
                    if (editable) {
                        TextField(
                            value = draft.title,
                            onValueChange = { onChange(draft.copy(title = DictationChecks.oneLine(it))) },
                            textStyle = MaterialTheme.typography.titleSmall,
                            singleLine = false,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = if ("title" in changed) highlight else Color.Transparent,
                                unfocusedContainerColor = if ("title" in changed || "new" in changed) highlight else Color.Transparent,
                                focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(draft.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
                if (onRemove != null) {
                    IconButton(onClick = onRemove) { Icon(Icons.Outlined.Close, contentDescription = "Remove “${draft.title}”") }
                }
            }
            DraftNotes(
                notes = draft.notes,
                editable = editable,
                guessed = "notes" in guessed,
                changed = "notes" in changed,
                onChange = { onChange(draft.copy(notes = it)) },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                DueChip(draft, editable, "when" in guessed, "when" in changed, onChange, onDueClick)
                ProjectChip(draft, context, newProjects, editable, "project" in guessed, "project" in changed, onChange)
                for (label in draft.labels) {
                    DraftChip(
                        text = "#" + label.title + if (label.id == null) " (new)" else "",
                        guessed = "labels" in guessed,
                        changed = "labels" in changed,
                        onClick = if (editable) ({ onChange(draft.copy(labels = draft.labels - label)) }) else null,
                        trailingClose = editable,
                    )
                }
                PriorityChip(draft, editable, "priority" in guessed, "priority" in changed, onChange)
                draft.repeat?.let { r ->
                    DraftChip(
                        text = if (r.every == 1) "Every ${r.unit}" else "Every ${r.every} ${r.unit}s",
                        guessed = "repeat" in guessed,
                        changed = "repeat" in changed,
                        onClick = if (editable) ({ onChange(draft.copy(repeat = null)) }) else null,
                        trailingClose = editable,
                    )
                }
                draft.reminder?.let { r ->
                    val text = r.beforeDueMinutes?.let { m -> if (m == 0) "Remind at due time" else "Remind ${minutesText(m)} before" }
                        ?: r.at?.let { at -> "Remind " + DateDisplay.midSentence(DateDisplay.format(DateContext.CHIP, at, LocalClockDay.current.date, false, LocalDateFormat.current)) }
                        ?: ""
                    DraftChip(text, "reminder" in guessed, "reminder" in changed, if (editable) ({ onChange(draft.copy(reminder = null)) }) else null, editable)
                }
                draft.place?.let { p ->
                    val name = context?.places?.firstOrNull { it.id == p.placeId }?.name ?: "a place"
                    DraftChip(
                        text = (if (p.trigger == "leave") "When I leave " else "When I arrive at ") + name,
                        guessed = "place" in guessed,
                        changed = "place" in changed,
                        onClick = if (editable) ({ onChange(draft.copy(place = null)) }) else null,
                        trailingClose = editable,
                    )
                }
            }
            if (draft.subtasks.isNotEmpty()) {
                Column(Modifier.padding(start = 4.dp, top = 4.dp).dashedOutline(outline, "subtasks" in guessed)) {
                    for (step in draft.subtasks) {
                        Text("• $step", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 1.dp))
                    }
                }
            }
            for (d in dropped) {
                Text(
                    droppedText(d, brainDump),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                )
            }
        }
    }
}

/**
 * A draft's notes: read-only on a card that cannot change. Otherwise a field without a border at
 * rest, up to [NOTES_MAX_LINES] before it scrolls, or a quiet "Add notes" when there are none.
 */
@Composable
private fun DraftNotes(notes: String, editable: Boolean, guessed: Boolean, changed: Boolean, onChange: (String) -> Unit) {
    val outline = MaterialTheme.colorScheme.tertiary
    if (!editable) {
        if (notes.isNotBlank()) {
            Text(
                notes,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp, end = 8.dp, bottom = 4.dp).dashedOutline(outline, guessed),
            )
        }
        return
    }
    var adding by remember { mutableStateOf(false) }
    if (notes.isEmpty() && !adding) {
        TextButton(onClick = { adding = true }) { Text("Add notes") }
        return
    }
    val focus = remember { FocusRequester() }
    val highlight = MaterialTheme.colorScheme.secondaryContainer
    TextField(
        value = notes,
        onValueChange = onChange,
        textStyle = MaterialTheme.typography.bodySmall,
        placeholder = { Text("Add notes", style = MaterialTheme.typography.bodySmall) },
        maxLines = NOTES_MAX_LINES,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = if (changed) highlight else Color.Transparent,
            unfocusedContainerColor = if (changed) highlight else Color.Transparent,
            focusedIndicatorColor = MaterialTheme.colorScheme.primary,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 8.dp, bottom = 4.dp)
            .dashedOutline(outline, guessed)
            .focusRequester(focus)
            .onFocusChanged { if (!it.isFocused && notes.isEmpty()) adding = false },
    )
    LaunchedEffect(adding) { if (adding) focus.requestFocus() }
}

private const val NOTES_MAX_LINES = 6

private fun minutesText(minutes: Int): String = when {
    minutes % 1440 == 0 -> (minutes / 1440).let { if (it == 1) "1 day" else "$it days" }
    minutes % 60 == 0 -> (minutes / 60).let { if (it == 1) "1 hour" else "$it hours" }
    else -> "$minutes min"
}

@Composable
internal fun DraftChip(text: String, guessed: Boolean, changed: Boolean, onClick: (() -> Unit)?, trailingClose: Boolean = false) {
    AssistChip(
        onClick = { onClick?.invoke() },
        enabled = true,
        label = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = if (trailingClose && onClick != null) ({ Icon(Icons.Outlined.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp)) }) else null,
        colors = if (changed) AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else AssistChipDefaults.assistChipColors(),
        modifier = Modifier.dashedOutline(MaterialTheme.colorScheme.tertiary, guessed),
    )
}

@Composable
private fun DueChip(
    draft: CheckedDraft,
    editable: Boolean,
    guessed: Boolean,
    changed: Boolean,
    onChange: (CheckedDraft) -> Unit,
    onDueClick: (() -> Unit)?,
) {
    val day = LocalClockDay.current
    val format = LocalDateFormat.current
    var open by remember { mutableStateOf(false) }
    val text = draft.due?.let { DateDisplay.format(DateContext.CHIP, it, day.date, !draft.dueHasTime, format) } ?: "No date"
    Box {
        DraftChip(text, guessed, changed, onDueClick ?: if (editable) ({ open = true }) else null)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            fun pick(date: LocalDate?) {
                open = false
                onChange(draft.copy(due = date?.atTime(DictationChecks.DATE_ONLY), dueHasTime = false, reminder = if (date == null && draft.reminder?.beforeDueMinutes != null) null else draft.reminder))
            }
            @Composable
            fun quickChoice(label: String, date: LocalDate) {
                DropdownMenuItem(
                    text = { Text(label) },
                    trailingIcon = {
                        Text(
                            DateDisplay.formatWeekdayDate(date, day.date, format),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = { pick(date) },
                )
            }
            quickChoice("Today", day.date)
            quickChoice("Tomorrow", day.date.plus(1, DateTimeUnit.DAY))
            quickChoice("Next week", com.rendyhd.vicu.util.DueDates.nextWeekStart(day.date))
            DropdownMenuItem(text = { Text("No date") }, onClick = { pick(null) })
        }
    }
}

@Composable
private fun ProjectChip(
    draft: CheckedDraft,
    context: DictationContext?,
    newProjects: List<CheckedProject>,
    editable: Boolean,
    guessed: Boolean,
    changed: Boolean,
    onChange: (CheckedDraft) -> Unit,
) {
    val projects = context?.projects.orEmpty()
    val name = draft.projectRef?.let { ref -> newProjects.firstOrNull { it.ref == ref }?.let { it.title + " (new)" } }
        ?: projects.firstOrNull { it.id == draft.projectId }?.path
        ?: "Inbox"
    var open by remember { mutableStateOf(false) }
    Box {
        DraftChip(name, guessed, changed, if (editable && (projects.isNotEmpty() || newProjects.isNotEmpty())) ({ open = true }) else null)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (p in newProjects) {
                DropdownMenuItem(text = { Text(p.title + " (new)") }, onClick = {
                    open = false
                    onChange(draft.copy(projectRef = p.ref, guessed = draft.guessed - "project"))
                })
            }
            for (p in projects) {
                DropdownMenuItem(text = { Text(p.path) }, onClick = {
                    open = false
                    onChange(draft.copy(projectId = p.id, projectRef = null, guessed = draft.guessed - "project"))
                })
            }
        }
    }
}

@Composable
private fun PriorityChip(draft: CheckedDraft, editable: Boolean, guessed: Boolean, changed: Boolean, onChange: (CheckedDraft) -> Unit) {
    if (draft.priority == null && !editable) return
    var open by remember { mutableStateOf(false) }
    val names = listOf("No priority", "Low", "Medium", "High", "Urgent")
    Box {
        DraftChip(if (draft.priority == null) "Priority" else names[draft.priority], guessed, changed, if (editable) ({ open = true }) else null)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            names.forEachIndexed { value, label ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    open = false
                    onChange(draft.copy(priority = value.takeIf { it > 0 }))
                })
            }
        }
    }
}

@Suppress("unused")
private fun labelTitles(labels: List<DraftLabel>) = labels.joinToString { it.title }
