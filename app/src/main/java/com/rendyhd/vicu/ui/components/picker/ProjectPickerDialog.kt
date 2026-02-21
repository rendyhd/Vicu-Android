package com.rendyhd.vicu.ui.components.picker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.domain.model.Project

private fun parseHexColor(hex: String): Color? {
    if (hex.isBlank()) return null
    return try {
        Color(android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex"))
    } catch (_: Exception) {
        null
    }
}

@Composable
fun ProjectPickerDialog(
    projects: List<Project>,
    selectedProjectId: Long?,
    onProjectSelected: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to project") },
        text = {
            LazyColumn {
                items(projects.filter { !it.isArchived }, key = { it.id }) { project ->
                    val isSelected = project.id == selectedProjectId
                    val indent = if (project.parentProjectId > 0) 24.dp else 0.dp

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

                        Text(
                            text = project.title,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )

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
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
