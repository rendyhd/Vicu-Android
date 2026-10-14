package com.rendyhd.vicu.ui.components.picker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.util.parseHexColor

@Composable
fun ProjectPickerDialog(
    projects: List<Project>,
    selectedProjectId: Long?,
    onProjectSelected: (Long) -> Unit,
    onDismiss: () -> Unit,
    inboxProjectId: Long = 0L,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val rows = remember(projects, inboxProjectId, query) {
        projectPickerRows(projects, inboxProjectId, query)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Project") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search projects...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (rows.isEmpty()) {
                    Text(
                        text = "No project matches",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(rows, key = { it.project.id }, contentType = { "project" }) { row ->
                        val project = row.project
                        val isSelected = project.id == selectedProjectId
                        val indent = (row.depth * 16).coerceAtMost(48).dp

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onProjectSelected(project.id)
                                    onDismiss()
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp)
                                .padding(start = indent),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            // Color dot
                            val color = parseHexColor(project.hexColor)
                                ?: MaterialTheme.colorScheme.primary

                            Surface(
                                shape = CircleShape,
                                color = color,
                                modifier = Modifier.size(10.dp),
                            ) {}

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = project.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                row.parentTitle?.let { parent ->
                                    Text(
                                        text = "in $parent",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            if (isSelected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
