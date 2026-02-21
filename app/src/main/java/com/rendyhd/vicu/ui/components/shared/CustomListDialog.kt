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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.CustomListFilter
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import java.util.UUID

private val DUE_DATE_OPTIONS = listOf(
    "all" to "All",
    "overdue" to "Overdue",
    "today" to "Today",
    "this_week" to "This week",
    "this_month" to "This month",
    "has_due_date" to "Has due date",
    "no_due_date" to "No due date",
)

private val SORT_BY_OPTIONS = listOf(
    "due_date" to "Due date",
    "created" to "Created",
    "updated" to "Updated",
    "priority" to "Priority",
)

private val ORDER_OPTIONS = listOf(
    "asc" to "Ascending",
    "desc" to "Descending",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomListDialog(
    customList: CustomList? = null,
    projects: List<Project>,
    labels: List<Label>,
    onSave: (CustomList) -> Unit,
    onDismiss: () -> Unit,
) {
    val isEdit = customList != null
    var name by remember { mutableStateOf(customList?.name ?: "") }
    var selectedProjectIds by remember {
        mutableStateOf(customList?.filter?.projectIds?.toSet() ?: emptySet())
    }
    var sortBy by remember { mutableStateOf(customList?.filter?.sortBy ?: "due_date") }
    var orderBy by remember { mutableStateOf(customList?.filter?.orderBy ?: "asc") }
    var dueDateFilter by remember { mutableStateOf(customList?.filter?.dueDateFilter ?: "all") }
    var selectedLabelIds by remember {
        mutableStateOf(customList?.filter?.labelIds?.toSet() ?: emptySet())
    }
    var includeDone by remember { mutableStateOf(customList?.filter?.includeDone ?: false) }
    var includeTodayAllProjects by remember {
        mutableStateOf(customList?.filter?.includeTodayAllProjects ?: false)
    }

    var showProjectPicker by remember { mutableStateOf(false) }
    var showLabelPicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "Edit List" else "New List") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Project filter
                Text(
                    text = "Projects",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showProjectPicker = !showProjectPicker },
                    shape = MaterialTheme.shapes.small,
                    tonalElevation = 1.dp,
                ) {
                    Text(
                        text = if (selectedProjectIds.isEmpty()) "All projects"
                        else "${selectedProjectIds.size} selected",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (showProjectPicker) {
                    LazyColumn(modifier = Modifier.heightIn(max = 150.dp)) {
                        items(
                            projects.filter { !it.isArchived },
                            key = { it.id },
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

                Spacer(modifier = Modifier.height(12.dp))

                // Sort by
                DropdownSelector(
                    label = "Sort by",
                    options = SORT_BY_OPTIONS,
                    selected = sortBy,
                    onSelect = { sortBy = it },
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Order
                DropdownSelector(
                    label = "Order",
                    options = ORDER_OPTIONS,
                    selected = orderBy,
                    onSelect = { orderBy = it },
                )

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
                        items(labels, key = { it.id }) { label ->
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
                                id = customList?.id ?: UUID.randomUUID().toString(),
                                name = name.trim(),
                                icon = customList?.icon ?: "",
                                filter = CustomListFilter(
                                    projectIds = selectedProjectIds.toList(),
                                    sortBy = sortBy,
                                    orderBy = orderBy,
                                    dueDateFilter = dueDateFilter,
                                    labelIds = selectedLabelIds.toList(),
                                    includeDone = includeDone,
                                    includeTodayAllProjects = includeTodayAllProjects,
                                ),
                            )
                        )
                    }
                },
                enabled = name.isNotBlank(),
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

private fun parseHexColor(hex: String): Color? {
    if (hex.isBlank()) return null
    return try {
        val normalized = if (hex.startsWith("#")) hex else "#$hex"
        Color(android.graphics.Color.parseColor(normalized))
    } catch (_: Exception) {
        null
    }
}
