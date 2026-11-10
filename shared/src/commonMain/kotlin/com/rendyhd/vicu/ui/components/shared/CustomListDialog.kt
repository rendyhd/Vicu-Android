package com.rendyhd.vicu.ui.components.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.SuggestionChip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import com.rendyhd.vicu.domain.model.ListSortKey
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.CustomListFilter
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.ui.components.collab.LocalCollab
import com.rendyhd.vicu.util.CustomListFilterBuilder
import com.rendyhd.vicu.util.parseHexColor
import com.rendyhd.vicu.util.randomUuid

private val DUE_DATE_OPTIONS = listOf(
    "all" to "All",
    "overdue" to "Overdue",
    "today" to "Today",
    "this_week" to "This week",
    "this_month" to "This month",
    "has_due_date" to "Has due date",
    "no_due_date" to "No due date",
)

/** The sorts the editor offers: every key [CustomListFilterBuilder.sortTasks] understands. */
internal val SORT_BY_OPTIONS = listOf(
    "due_date" to "Due date",
    "start_date" to "Start date",
    "end_date" to "End date",
    "created" to "Created",
    "updated" to "Updated",
    "done_at" to "Completed",
    "priority" to "Priority",
    "percent_done" to "Progress",
    "title" to "Title",
    "done" to "Done",
    "id" to "Order created",
    "position" to "Position",
)

/** The two orders of [sortBy], named for the field ("Soonest first", "A to Z"). */
internal fun orderOptions(sortBy: String): List<Pair<String, String>> {
    val (asc, desc) = when (sortBy) {
        "due_date", "start_date", "end_date" -> "Soonest first" to "Latest first"
        "created", "updated", "done_at", "id" -> "Oldest first" to "Newest first"
        "priority" -> "Lowest first" to "Highest first"
        "percent_done" -> "Least done first" to "Most done first"
        "title" -> "A to Z" to "Z to A"
        "done" -> "Open first" to "Done first"
        else -> "Ascending" to "Descending"
    }
    return listOf("asc" to asc, "desc" to desc)
}

/** "Then by" choices: None and every key but the first. */
internal fun thenByOptions(sortBy: String, current: String): List<Pair<String, String>> {
    val others = SORT_BY_OPTIONS.filter { it.first != sortBy }
    val kept = if (current.isEmpty() || others.any { it.first == current }) others else others + (current to "Set by a newer version")
    return listOf("" to "None") + kept
}

/** "Changed" counts local days with today included (cross-app semantics 3.3); 0 is no condition. */
private val CHANGED_OPTIONS = listOf(
    0 to "Any time",
    1 to "Today",
    3 to "In the last 3 days",
    7 to "In the last 7 days",
    30 to "In the last 30 days",
)

/** The "Changed" choices, with a value from another device that is not among them kept selectable. */
internal fun changedOptions(current: Int?): List<Pair<String, String>> {
    val offered = CHANGED_OPTIONS.map { (days, label) -> days.toString() to label }
    return if (current == null || current < 1 || CHANGED_OPTIONS.any { it.first == current }) offered
    else offered + (current.toString() to "In the last $current days")
}

internal val ASSIGNED_OPTIONS = listOf(
    "" to "Any task",
    "me" to "To me",
    "others" to "To someone else",
    "nobody" to "To nobody",
)

internal val COMMENTS_OPTIONS = listOf(
    "" to "Any task",
    "unread" to "Unread comments",
    "any" to "Has comments",
)

/** [options] plus [value] when this version does not offer it (from a newer app), so saving keeps it. */
internal fun withKeptValue(options: List<Pair<String, String>>, value: String): List<Pair<String, String>> =
    if (options.any { it.first == value }) options else options + (value to "Set by a newer version")

/** The switch position for a stored `include_overdue`: an absent key means on. */
internal fun includeOverdueSwitchOn(stored: Boolean?): Boolean = stored != false

/** What the switch stores: the key stays absent unless the user turned overdue off. */
internal fun includeOverdueToStore(switchOn: Boolean): Boolean? = if (switchOn) null else false

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomListDialog(
    customList: CustomList? = null,
    projects: List<Project>,
    labels: List<Label>,
    onSave: (CustomList) -> Unit,
    onDismiss: () -> Unit,
    inboxProjectId: Long = 0L,
) {
    val sortedProjects = remember(projects, inboxProjectId) {
        projects.filter { !it.isArchived }
            .sortedWith(
                compareBy<Project> { it.id != inboxProjectId }
                    .thenBy { it.position },
            )
    }
    val isEdit = customList != null
    var name by remember { mutableStateOf(customList?.name ?: "") }
    var icon by remember { mutableStateOf(customList?.icon?.ifBlank { "filter_list" } ?: "filter_list") }
    var selectedProjectIds by remember {
        mutableStateOf(customList?.filter?.projectIds?.toSet() ?: emptySet())
    }
    var projectFilterMode by remember {
        mutableStateOf(customList?.filter?.projectFilterMode ?: "include")
    }
    var sortBy by remember { mutableStateOf(customList?.filter?.sortBy ?: "due_date") }
    var orderBy by remember { mutableStateOf(customList?.filter?.orderBy ?: "asc") }
    // The editor shows one "then by"; keys after it (an imported filter's) are kept.
    var thenBy by remember { mutableStateOf(customList?.filter?.thenBy?.firstOrNull()?.sortBy.orEmpty()) }
    var thenOrder by remember { mutableStateOf(customList?.filter?.thenBy?.firstOrNull()?.orderBy ?: "asc") }
    val namedLabels = remember(labels) { labels.map { RuleEditing.Named(it.id, it.title) } }
    val namedProjects = remember(projects) { projects.map { RuleEditing.Named(it.id, it.title) } }
    var rule by remember {
        val shown = RuleEditing.toDisplay(customList?.filter?.rule, namedLabels, namedProjects)
        mutableStateOf(TextFieldValue(shown, TextRange(shown.length)))
    }
    var ruleIncludeNulls by remember { mutableStateOf(customList?.filter?.ruleIncludeNulls ?: false) }
    val savedRule = remember(rule.text, namedLabels, namedProjects) {
        RuleEditing.fromDisplay(rule.text, namedLabels, namedProjects)
    }
    var ruleFocused by remember { mutableStateOf(false) }
    var dueDateFilter by remember { mutableStateOf(customList?.filter?.dueDateFilter ?: "all") }
    var selectedLabelIds by remember {
        mutableStateOf(customList?.filter?.labelIds?.toSet() ?: emptySet())
    }
    var addToProjectId by remember {
        mutableStateOf(customList?.filter?.addToProjectId ?: 0L)
    }
    var includeDone by remember { mutableStateOf(customList?.filter?.includeDone ?: false) }
    var includeTodayAllProjects by remember {
        mutableStateOf(customList?.filter?.includeTodayAllProjects ?: false)
    }
    var includeOverdue by remember {
        mutableStateOf(includeOverdueSwitchOn(customList?.filter?.includeOverdue))
    }
    var changedWithinDays by remember { mutableStateOf(customList?.filter?.changedDays ?: 0) }
    var assigned by remember { mutableStateOf(customList?.filter?.assigned.orEmpty()) }
    var comments by remember { mutableStateOf(customList?.filter?.comments.orEmpty()) }
    var sharedOnly by remember { mutableStateOf(customList?.filter?.sharedOnly ?: false) }
    // The collaboration conditions only mean something once a project is shared; a list that
    // already has one (synced from another device) shows them so they can be changed.
    val anyShared = LocalCollab.current.state.anyShared
    val hadCollaboration = remember {
        customList?.filter?.let { !it.assigned.isNullOrEmpty() || !it.comments.isNullOrEmpty() || it.sharedOnly } == true
    }

    var showProjectPicker by remember { mutableStateOf(false) }
    var showLabelPicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "Edit List" else "New List") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (customList?.savedFilterId != null) {
                    ImportedListNote()
                    Spacer(modifier = Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Icon",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                IconPicker(selectedKey = icon, onSelect = { icon = it }, allowNone = true)

                Spacer(modifier = Modifier.height(16.dp))

                // Project filter
                Text(
                    text = "Projects",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = projectFilterMode == "include",
                        onClick = { projectFilterMode = "include" },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) {
                        Text("Include")
                    }
                    SegmentedButton(
                        selected = projectFilterMode == "exclude",
                        onClick = { projectFilterMode = "exclude" },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) {
                        Text("Exclude")
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showProjectPicker = !showProjectPicker },
                    shape = MaterialTheme.shapes.small,
                    tonalElevation = 1.dp,
                ) {
                    Text(
                        text = if (selectedProjectIds.isEmpty()) {
                            if (projectFilterMode == "exclude") "None excluded" else "All projects"
                        } else {
                            if (projectFilterMode == "exclude") "${selectedProjectIds.size} excluded"
                            else "${selectedProjectIds.size} included"
                        },
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (showProjectPicker) {
                    LazyColumn(modifier = Modifier.heightIn(max = 150.dp)) {
                        items(
                            sortedProjects,
                            key = { it.id },
                            contentType = { "project" },
                        ) { project ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedProjectIds = if (project.id in selectedProjectIds)
                                            selectedProjectIds - project.id
                                        else
                                            selectedProjectIds + project.id
                                    }
                                    .padding(vertical = 2.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = project.id in selectedProjectIds,
                                    onCheckedChange = {
                                        selectedProjectIds = if (project.id in selectedProjectIds)
                                            selectedProjectIds - project.id
                                        else
                                            selectedProjectIds + project.id
                                    },
                                )
                                Text(
                                    text = project.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                // Due date filter
                DropdownSelector(
                    label = "Due date filter",
                    options = DUE_DATE_OPTIONS,
                    selected = dueDateFilter,
                    onSelect = { dueDateFilter = it },
                )

                // Only the today / this week / this month windows have an overdue part to switch.
                if (CustomListFilterBuilder.windowHonorsIncludeOverdue(dueDateFilter)) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Include overdue tasks", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = includeOverdue, onCheckedChange = { includeOverdue = it })
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                DropdownSelector(
                    label = "Changed",
                    options = changedOptions(changedWithinDays),
                    selected = changedWithinDays.toString(),
                    onSelect = { changedWithinDays = it.toIntOrNull() ?: 0 },
                )

                if (anyShared || hadCollaboration) {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Shared projects",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    DropdownSelector(
                        label = "Assigned",
                        options = withKeptValue(ASSIGNED_OPTIONS, assigned),
                        selected = assigned,
                        onSelect = { assigned = it },
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    DropdownSelector(
                        label = "Comments",
                        options = withKeptValue(COMMENTS_OPTIONS, comments),
                        selected = comments,
                        onSelect = { comments = it },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Only tasks in shared projects", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = sharedOnly, onCheckedChange = { sharedOnly = it })
                    }
                    Text(
                        text = "Assigned and unread comments only match tasks in projects someone else can see.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                // The rule, in Vikunja's filter syntax (cross-app semantics 3.5).
                Text(
                    text = "Rule",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                val problem = (savedRule as? RuleEditing.Saved.Problem)?.message
                OutlinedTextField(
                    value = rule,
                    onValueChange = { rule = it },
                    placeholder = { Text("done = false && priority > 2", fontFamily = FontFamily.Monospace) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    isError = problem != null,
                    supportingText = {
                        Text(
                            problem
                                ?: "Tasks must also match this. It is written as in Vikunja's saved filters.",
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                    ),
                    minLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { ruleFocused = it.isFocused }
                        .semantics { contentDescription = "Rule" },
                )
                if (ruleFocused) {
                    val suggestions = remember(rule, namedLabels, namedProjects) {
                        RuleEditing.suggest(rule.text, rule.selection.end, namedLabels, namedProjects)
                    }
                    if (suggestions.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            suggestions.forEach { suggestion ->
                                SuggestionChip(
                                    onClick = {
                                        val text = rule.text.replaceRange(suggestion.start, suggestion.end, suggestion.insert)
                                        val cursor = suggestion.start + suggestion.insert.length
                                        rule = TextFieldValue(text, TextRange(cursor))
                                    },
                                    label = { Text(suggestion.label) },
                                )
                            }
                        }
                    }
                }
                if (rule.text.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Include tasks without a value",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = ruleIncludeNulls, onCheckedChange = { ruleIncludeNulls = it })
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                // Sort by, then by (cross-app semantics 3.6); each order is named for its field.
                // One field per line: side by side, a phone left the order about 100 dp, and
                // "Soonest first" broke inside the word.
                DropdownSelector(
                    label = "Sort by",
                    options = withKeptValue(SORT_BY_OPTIONS, sortBy),
                    selected = sortBy,
                    onSelect = {
                        sortBy = it
                        if (thenBy == it) thenBy = ""
                    },
                )
                Spacer(modifier = Modifier.height(8.dp))
                DropdownSelector(
                    label = "Order",
                    options = orderOptions(sortBy),
                    selected = orderBy,
                    onSelect = { orderBy = it },
                )

                Spacer(modifier = Modifier.height(12.dp))

                DropdownSelector(
                    label = "Then by",
                    options = thenByOptions(sortBy, thenBy),
                    selected = thenBy,
                    onSelect = { thenBy = it },
                )
                if (thenBy.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    DropdownSelector(
                        label = "Then order",
                        options = orderOptions(thenBy),
                        selected = thenOrder,
                        onSelect = { thenOrder = it },
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                // Label filter
                Text(
                    text = "Labels",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showLabelPicker = !showLabelPicker },
                    shape = MaterialTheme.shapes.small,
                    tonalElevation = 1.dp,
                ) {
                    Text(
                        text = if (selectedLabelIds.isEmpty()) "All labels"
                        else "${selectedLabelIds.size} selected",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (showLabelPicker) {
                    LazyColumn(modifier = Modifier.heightIn(max = 150.dp)) {
                        items(labels, key = { it.id }, contentType = { "label" }) { label ->
                            val dotColor = parseHexColor(label.hexColor)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedLabelIds = if (label.id in selectedLabelIds)
                                            selectedLabelIds - label.id
                                        else
                                            selectedLabelIds + label.id
                                    }
                                    .padding(vertical = 2.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = label.id in selectedLabelIds,
                                    onCheckedChange = {
                                        selectedLabelIds = if (label.id in selectedLabelIds)
                                            selectedLabelIds - label.id
                                        else
                                            selectedLabelIds + label.id
                                    },
                                )
                                if (dotColor != null) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(dotColor),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(
                                    text = label.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                // Add tasks to project
                Text(
                    text = "Add tasks to",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                AddToProjectSelector(
                    projects = sortedProjects,
                    selectedProjectId = addToProjectId,
                    onSelect = { addToProjectId = it },
                )

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                // Toggles
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Include completed", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = includeDone, onCheckedChange = { includeDone = it })
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Include today from all projects", style = MaterialTheme.typography.bodySmall)
                    Switch(
                        checked = includeTodayAllProjects,
                        onCheckedChange = { includeTodayAllProjects = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank()) {
                        onSave(
                            CustomList(
                                id = customList?.id ?: randomUuid(),
                                name = name.trim(),
                                icon = icon,
                                // A copy, so conditions this editor does not show (priorities set
                                // on desktop) are kept.
                                filter = (customList?.filter ?: CustomListFilter()).copy(
                                    projectIds = selectedProjectIds.toList(),
                                    projectFilterMode = projectFilterMode,
                                    addToProjectId = addToProjectId,
                                    sortBy = sortBy,
                                    orderBy = orderBy,
                                    dueDateFilter = dueDateFilter,
                                    labelIds = selectedLabelIds.toList(),
                                    includeDone = includeDone,
                                    includeTodayAllProjects = includeTodayAllProjects,
                                    includeOverdue = includeOverdueToStore(includeOverdue),
                                    assigned = assigned.ifEmpty { null },
                                    comments = comments.ifEmpty { null },
                                    sharedOnly = sharedOnly,
                                    changedWithinDays = changedWithinDays.takeIf { it >= 1 },
                                    rule = (savedRule as? RuleEditing.Saved.Rule)?.text,
                                    ruleIncludeNulls = ruleIncludeNulls && rule.text.isNotBlank(),
                                    thenBy = if (thenBy.isEmpty()) emptyList()
                                    else listOf(ListSortKey(thenBy, thenOrder)) + customList?.filter?.thenBy.orEmpty().drop(1),
                                ),
                                savedFilterId = customList?.savedFilterId,
                            )
                        )
                    }
                },
                enabled = name.isNotBlank() && savedRule is RuleEditing.Saved.Rule,
            ) {
                Text(if (isEdit) "Save" else "Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/** The note at the top of an imported list's editor (cross-app semantics 3.7). */
@Composable
private fun ImportedListNote() {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                "Imported from a Vikunja saved filter. Changes here stay in Vicu and won't update the saved filter on your server.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownSelector(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.find { it.first == selected }?.second ?: selected

    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(4.dp))

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (value, displayLabel) ->
                DropdownMenuItem(
                    text = { Text(displayLabel) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddToProjectSelector(
    projects: List<Project>,
    selectedProjectId: Long,
    onSelect: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = if (selectedProjectId == 0L) "Inbox (default)"
    else projects.find { it.id == selectedProjectId }?.title ?: "Unavailable project (uses Inbox)"

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("Inbox (default)") },
                onClick = {
                    onSelect(0L)
                    expanded = false
                },
            )
            projects.forEach { project ->
                DropdownMenuItem(
                    text = { Text(project.title) },
                    onClick = {
                        onSelect(project.id)
                        expanded = false
                    },
                )
            }
        }
    }
}
