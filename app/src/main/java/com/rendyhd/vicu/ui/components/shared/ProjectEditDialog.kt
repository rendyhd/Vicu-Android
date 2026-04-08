package com.rendyhd.vicu.ui.components.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.domain.model.Project

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ProjectEditDialog(
    project: Project? = null,
    projects: List<Project>,
    onSave: (name: String, hexColor: String, parentProjectId: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val isEdit = project != null
    var name by remember { mutableStateOf(project?.title ?: "") }
    var selectedColor by remember {
        mutableStateOf(
            project?.hexColor?.ifBlank { null } ?: PRESET_COLORS.first()
        )
    }
    var parentId by remember { mutableLongStateOf(project?.parentProjectId ?: 0L) }
    var parentDropdownExpanded by remember { mutableStateOf(false) }

    // Exclude the project being edited (and its children) from parent options
    val availableParents = projects.filter { it.id != project?.id }

    val parentLabel = if (parentId == 0L) {
        "None (top-level)"
    } else {
        availableParents.find { it.id == parentId }?.title ?: "None (top-level)"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "Edit Project" else "Create Project") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(16.dp))

                ExposedDropdownMenuBox(
                    expanded = parentDropdownExpanded,
                    onExpandedChange = { parentDropdownExpanded = it },
                ) {
                    OutlinedTextField(
                        value = parentLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Parent Project") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = parentDropdownExpanded)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    )

                    ExposedDropdownMenu(
                        expanded = parentDropdownExpanded,
                        onDismissRequest = { parentDropdownExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("None (top-level)") },
                            onClick = {
                                parentId = 0L
                                parentDropdownExpanded = false
                            },
                        )
                        availableParents.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.title) },
                                onClick = {
                                    parentId = p.id
                                    parentDropdownExpanded = false
                                },
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Color",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(8.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PRESET_COLORS.forEach { hex ->
                        val color = Color(android.graphics.Color.parseColor(hex))
                        val normalizedSelected = selectedColor.let {
                            if (it.startsWith("#")) it else "#$it"
                        }
                        val isSelected = hex == normalizedSelected

                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(color)
                                .then(
                                    if (isSelected) Modifier.border(
                                        3.dp,
                                        MaterialTheme.colorScheme.onSurface,
                                        CircleShape,
                                    )
                                    else Modifier
                                )
                                .clickable { selectedColor = hex },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank()) {
                        onSave(name.trim(), selectedColor, parentId)
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
