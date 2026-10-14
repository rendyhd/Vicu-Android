package com.rendyhd.vicu.ui.components.picker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.util.PRESET_COLORS
import com.rendyhd.vicu.util.parseHexColor

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LabelPickerDialog(
    allLabels: List<Label>,
    selectedLabelIds: Set<Long>,
    onToggleLabel: (Long) -> Unit,
    onCreateLabel: (name: String, hexColor: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var showCreateSection by remember { mutableStateOf(false) }
    var newLabelName by remember { mutableStateOf("") }
    var selectedColor by remember { mutableStateOf(PRESET_COLORS.first()) }

    val filteredLabels = remember(allLabels, searchQuery) {
        if (searchQuery.isBlank()) allLabels
        else allLabels.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Labels") },
        text = {
            Column {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search labels...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (filteredLabels.isEmpty()) {
                    Text(
                        text = if (allLabels.isEmpty()) "No labels yet" else "No label matches",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                // As tall as the labels need, up to what the dialog has room for: the list gives
                // way when the create section opens or the keyboard is up.
                LazyColumn(modifier = Modifier.weight(1f, fill = false).heightIn(max = 320.dp)) {
                    items(filteredLabels, key = { it.id }, contentType = { "label" }) { label ->
                        val isChecked = label.id in selectedLabelIds
                        val labelColor = parseHexColor(label.hexColor)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleLabel(label.id) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { onToggleLabel(label.id) },
                            )
                            if (labelColor != null) {
                                Surface(
                                    shape = CircleShape,
                                    color = labelColor,
                                    modifier = Modifier.size(10.dp),
                                ) {}
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(
                                text = label.title,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                if (!showCreateSection) {
                    TextButton(
                        onClick = {
                            // What was searched for and not found is the likely name of the new label.
                            if (newLabelName.isBlank()) newLabelName = searchQuery.trim()
                            showCreateSection = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Create new label")
                    }
                } else {
                    Text("New label", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))

                    OutlinedTextField(
                        value = newLabelName,
                        onValueChange = { newLabelName = it },
                        placeholder = { Text("Label name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (newLabelName.isNotBlank()) {
                                    onCreateLabel(newLabelName.trim(), selectedColor)
                                    newLabelName = ""
                                    showCreateSection = false
                                }
                            },
                        ),
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PRESET_COLORS.forEach { hex ->
                            val color = parseHexColor(hex) ?: Color.Gray
                            val isSelected = hex == selectedColor

                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .then(
                                        if (isSelected) Modifier.border(
                                            2.dp,
                                            MaterialTheme.colorScheme.onSurface,
                                            CircleShape,
                                        )
                                        else Modifier
                                    )
                                    .clickable { selectedColor = hex },
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    TextButton(
                        onClick = {
                            if (newLabelName.isNotBlank()) {
                                onCreateLabel(newLabelName.trim(), selectedColor)
                                newLabelName = ""
                                showCreateSection = false
                            }
                        },
                        enabled = newLabelName.isNotBlank(),
                    ) {
                        Text("Create")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
        dismissButton = {},
    )
}
