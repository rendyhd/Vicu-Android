package com.rendyhd.vicu.ui.components.pro

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.data.repository.ProWorld
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.components.picker.MonthCalendar
import com.rendyhd.vicu.ui.components.shared.LocalClockDay
import com.rendyhd.vicu.ui.components.shared.VicuDragHandle
import com.rendyhd.vicu.util.PlannerTimeline
import com.rendyhd.vicu.util.RelationKind
import com.rendyhd.vicu.util.pro.People
import com.rendyhd.vicu.util.pro.ProScreens
import com.rendyhd.vicu.util.pro.ProjectMode
import com.rendyhd.vicu.util.pro.Promises
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The frame every Pro sheet of the editor shares: a title and a scrolling column. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProSheet(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { VicuDragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

/** A quiet note under a sheet's title. */
@Composable
private fun SheetNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * The start date (contract 21.3): a few quick days, a calendar and Remove. "Starts monday" without
 * a time is that day's local midnight.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProStartSheet(current: LocalDate?, onSet: (LocalDate?) -> Unit, onDismiss: () -> Unit) {
    val clockDay = LocalClockDay.current
    val today = clockDay.date
    ProSheet("Start date", onDismiss) {
        SheetNote("Until that day the task waits: it leaves Available.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProScreens.startChoices(today, clockDay.weekStart).forEach { (label, day) ->
                FilterChip(selected = current == day, onClick = { onSet(day) }, label = { Text(label) })
            }
        }
        MonthCalendar(selected = current, onSelect = { onSet(it) })
        if (current != null) {
            TextButton(onClick = { onSet(null) }) {
                Text("Remove start date", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** The estimate (contract 21.2, kept in the ledger): presets, a number of minutes, Remove. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProEstimateSheet(current: Int?, onSet: (Int?) -> Unit, onDismiss: () -> Unit) {
    var typed by rememberSaveable { mutableStateOf("") }
    ProSheet("Estimate", onDismiss) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProScreens.ESTIMATE_PRESETS.forEach { minutes ->
                FilterChip(
                    selected = current == minutes,
                    onClick = { onSet(minutes) },
                    label = { Text(PlannerTimeline.formatLength(minutes)) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = typed,
                onValueChange = { value -> typed = value.filter { it.isDigit() }.take(4) },
                label = { Text("Minutes") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            val minutes = typed.toIntOrNull()?.takeIf { it in 1..1440 }
            Button(enabled = minutes != null, onClick = { onSet(minutes) }) { Text("Set") }
        }
        if (current != null) {
            TextButton(onClick = { onSet(null) }) {
                Text("Remove estimate", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * "After" (contract 21.2): the open tasks this one waits on, each removable, and a search over
 * the open tasks to add one. A loop of tasks waiting on each other is said, as it is ignored.
 */
@Composable
fun ProAfterSheet(
    task: Task,
    world: ProWorld,
    onAdd: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    // The stored row follows the relation at once; the editor's copy may not have it yet.
    val live = world.openById[task.id] ?: task
    val blockers = live.relatedTasks[RelationKind.BLOCKED].orEmpty()
    val blockerIds = blockers.mapTo(HashSet()) { it.id }
    val matches = remember(query, world.open, blockerIds) {
        val words = query.trim().lowercase()
        if (words.isEmpty()) {
            emptyList()
        } else {
            world.open.filter { it.id != task.id && it.id !in blockerIds && it.title.lowercase().contains(words) }.take(8)
        }
    }
    ProSheet("After", onDismiss) {
        SheetNote("This task becomes available when these are done.")
        blockers.forEach { blocker ->
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    text = blocker.title.ifBlank { "Task ${blocker.id}" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onRemove(blocker.id) }) {
                    Icon(Icons.Outlined.Close, contentDescription = "Stop waiting on ${blocker.title.ifBlank { "this task" }}")
                }
            }
        }
        if (task.id in world.cycles) {
            Text(
                "These tasks wait on each other in a loop, so the loop is ignored. Remove one of them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Find a task") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        matches.forEach { other ->
            Text(
                text = other.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClickLabel = "Wait on this task") {
                        onAdd(other.id)
                        query = ""
                    }
                    .padding(vertical = 14.dp),
            )
        }
    }
}

/**
 * Makes the task a promise (contract 21.6): from someone (theirs) or for someone (yours), with
 * their words and how you will see it is done. A "Confirm with Jan: ..." task offers to become
 * Jan's promise (decision 31): the title keeps only the part after the colon.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProPromiseSheet(
    task: Task,
    world: ProWorld,
    onSave: (value: JsonObject, retitle: String?) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val existing = remember(world, task.id) { world.promiseFor(task) }
    val confirm = remember(task.title) { Promises.readConfirmTitle(task.title) }
    val confirmPerson = remember(confirm, world) { confirm?.let { People.matchPerson(it.first, world.peopleWithAliases).id } }
    var mine by rememberSaveable { mutableStateOf((existing?.get("dir") as? JsonPrimitive)?.content == "mine") }
    var person by rememberSaveable {
        mutableStateOf((existing?.get("person") as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong() ?: confirmPerson)
    }
    var words by rememberSaveable { mutableStateOf((existing?.get("words") as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()) }
    var doneWhen by rememberSaveable { mutableStateOf((existing?.get("done_when") as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()) }

    fun value(dirMine: Boolean, who: Long): JsonObject {
        val fields = LinkedHashMap<String, JsonElement>(existing ?: emptyMap())
        fields["dir"] = JsonPrimitive(if (dirMine) "mine" else "theirs")
        fields["person"] = JsonPrimitive(who)
        fields["words"] = words.trim().takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull
        fields["done_when"] = doneWhen.trim().takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull
        return JsonObject(fields)
    }

    ProSheet("Promise", onDismiss) {
        if (confirm != null && confirmPerson != null && existing == null) {
            Button(onClick = {
                onSave(value(dirMine = false, who = confirmPerson), confirm.second)
                onDismiss()
            }) { Text("Make it ${confirm.first}'s promise") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !mine, onClick = { mine = false }, label = { Text("They promised me") })
            FilterChip(selected = mine, onClick = { mine = true }, label = { Text("I promised them") })
        }
        if (world.people.isEmpty()) {
            SheetNote("Mark a label as a person first: open the label and choose This is a person, or use People.")
        } else {
            Text("Person", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                world.people.forEach { label ->
                    FilterChip(selected = person == label.id, onClick = { person = label.id }, label = { Text(label.title) })
                }
            }
        }
        OutlinedTextField(
            value = words,
            onValueChange = { words = it },
            label = { Text(if (mine) "What you said" else "In their words") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = doneWhen,
            onValueChange = { doneWhen = it },
            label = { Text("We will see it is done when") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (existing != null) {
                TextButton(onClick = {
                    onRemove()
                    onDismiss()
                }) { Text("Not a promise", color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.weight(1f))
            val who = person
            Button(enabled = who != null, onClick = {
                if (who != null) onSave(value(mine, who), null)
                onDismiss()
            }) { Text("Save") }
        }
    }
}

/** One choice of a radio list in a dialog; the whole row is the target. */
@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
    }
}

/**
 * A project's type (contract 21.2): parallel (the default), sequential (only the first task is
 * available) or single actions. A sequential project follows the task order; when that is not
 * Manual, the dialog offers to switch (decision 18). Synced.
 */
@Composable
fun ProjectTypeDialog(
    current: ProjectMode,
    orderIsManual: Boolean,
    onChoose: (ProjectMode) -> Unit,
    onManual: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Type") },
        text = {
            Column {
                listOf(
                    ProjectMode.PARALLEL to "Parallel",
                    ProjectMode.SEQUENTIAL to "Sequential",
                    ProjectMode.SINGLE to "Single actions",
                ).forEach { (mode, label) ->
                    RadioRow(label, current == mode) { onChoose(mode) }
                }
                if (current == ProjectMode.SEQUENTIAL && !orderIsManual) {
                    Text(
                        "A sequential project makes its first task available, in the project's order.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    TextButton(onClick = onManual) { Text("Order tasks by hand (Manual)") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** Active or On hold (contract 21.2): a project on hold makes its tasks wait. Synced. */
@Composable
fun ProjectStatusDialog(onHold: Boolean, onChoose: (Boolean) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Status") },
        text = {
            Column {
                RadioRow("Active", !onHold) { onChoose(false) }
                RadioRow("On hold", onHold) { onChoose(true) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
