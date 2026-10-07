package com.rendyhd.vicu.ui.components.picker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.RelationKind

/**
 * The line under a task in the picker: the project it is in and whether it is done, so tasks of
 * the same title can be told apart. Null when there is nothing to add.
 */
internal fun relationResultSubtitle(projectTitle: String?, done: Boolean): String? =
    listOfNotNull(projectTitle?.takeIf { it.isNotBlank() }, if (done) "Done" else null)
        .joinToString(" \u00B7 ")
        .ifEmpty { null }

@Composable
fun RelationTaskPickerDialog(
    searchResults: List<Task>,
    onQueryChange: (String) -> Unit,
    onConfirm: (otherTaskId: Long, relationKind: String) -> Unit,
    onDismiss: () -> Unit,
    /** The title of a project by id, for the line under each result. */
    projectTitleOf: (Long) -> String? = { null },
) {
    var selectedKind by remember { mutableStateOf(RelationKind.RELATED) }
    var query by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Relation") },
        text = {
            Column {
                // Kind selector
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    items(RelationKind.SELECTABLE, key = { it }, contentType = { "kind" }) { kind ->
                        FilterChip(
                            selected = kind == selectedKind,
                            onClick = { selectedKind = kind },
                            label = { Text(RelationKind.label(kind)) },
                        )
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        onQueryChange(it)
                    },
                    label = { Text("Search tasks") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                LazyColumn(modifier = Modifier.weight(1f, fill = false).heightIn(max = 280.dp)) {
                    items(searchResults, key = { it.id }, contentType = { "task" }) { task ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onConfirm(task.id, selectedKind) }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(
                                text = task.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (task.done) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                textDecoration = if (task.done) TextDecoration.LineThrough else null,
                            )
                            relationResultSubtitle(projectTitleOf(task.projectId), task.done)?.let { line ->
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
