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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.RelationKind

@Composable
fun RelationTaskPickerDialog(
    searchResults: List<Task>,
    onQueryChange: (String) -> Unit,
    onConfirm: (otherTaskId: Long, relationKind: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedKind by remember { mutableStateOf(RelationKind.RELATED) }
    var query by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add relation") },
        text = {
            Column {
                // Kind selector
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    items(RelationKind.SELECTABLE) { kind ->
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
                LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                    items(searchResults) { task ->
                        Text(
                            text = task.title,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onConfirm(task.id, selectedKind) }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
