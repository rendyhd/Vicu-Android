package com.rendyhd.vicu.ui.components.shared

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.domain.model.BottomBarSlot
import com.rendyhd.vicu.domain.model.BottomBarSlotType
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.Project

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BottomBarSlotEditor(
    currentSlot: BottomBarSlot,
    slotIndex: Int,
    projects: List<Project>,
    customLists: List<CustomList>,
    onSave: (BottomBarSlot) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedType by remember { mutableStateOf(currentSlot.type) }
    var selectedReferenceId by remember { mutableStateOf(currentSlot.referenceId) }
    var selectedIconKey by remember { mutableStateOf(currentSlot.iconKey) }
    var typeDropdownExpanded by remember { mutableStateOf(false) }

    val typeOptions = listOf(
        BottomBarSlotType.TODAY to "Today",
        BottomBarSlotType.UPCOMING to "Upcoming",
        BottomBarSlotType.ANYTIME to "Anytime",
        BottomBarSlotType.PROJECT to "Project",
        BottomBarSlotType.CUSTOM_LIST to "Custom List",
    )

    val isValid = when (selectedType) {
        BottomBarSlotType.TODAY, BottomBarSlotType.UPCOMING, BottomBarSlotType.ANYTIME -> true
        BottomBarSlotType.PROJECT -> selectedReferenceId.isNotEmpty() && projects.any { it.id.toString() == selectedReferenceId }
        BottomBarSlotType.CUSTOM_LIST -> selectedReferenceId.isNotEmpty() && customLists.any { it.id == selectedReferenceId }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bottom Bar Slot ${slotIndex + 1}") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Type dropdown
                Text(
                    "View type",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                ExposedDropdownMenuBox(
                    expanded = typeDropdownExpanded,
                    onExpandedChange = { typeDropdownExpanded = it },
                ) {
                    TextField(
                        value = typeOptions.first { it.first == selectedType }.second,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeDropdownExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = typeDropdownExpanded,
                        onDismissRequest = { typeDropdownExpanded = false },
                    ) {
                        typeOptions.forEach { (type, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    if (type != selectedType) {
                                        selectedType = type
                                        selectedReferenceId = ""
                                        selectedIconKey = ""
                                    }
                                    typeDropdownExpanded = false
                                },
                            )
                        }
                    }
                }

                // Reference picker for project/custom list
                if (selectedType == BottomBarSlotType.PROJECT && projects.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Select project",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(8.dp),
                            ),
                    ) {
                        items(projects, key = { it.id }) { project ->
                            val isSelected = selectedReferenceId == project.id.toString()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedReferenceId = project.id.toString() }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    project.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                if (isSelected) {
                                    Icon(
                                        Icons.Outlined.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                if (selectedType == BottomBarSlotType.CUSTOM_LIST && customLists.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Select list",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(8.dp),
                            ),
                    ) {
                        items(customLists, key = { it.id }) { list ->
                            val isSelected = selectedReferenceId == list.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedReferenceId = list.id }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    list.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                if (isSelected) {
                                    Icon(
                                        Icons.Outlined.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                // Icon picker (only for project/custom list)
                if (selectedType == BottomBarSlotType.PROJECT || selectedType == BottomBarSlotType.CUSTOM_LIST) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Choose icon",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        IconRegistry.PRESET_ICONS.forEach { iconOption ->
                            val isSelected = selectedIconKey == iconOption.key
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .then(
                                        if (isSelected) {
                                            Modifier.border(
                                                2.dp,
                                                MaterialTheme.colorScheme.primary,
                                                RoundedCornerShape(8.dp),
                                            )
                                        } else {
                                            Modifier.border(
                                                1.dp,
                                                MaterialTheme.colorScheme.outlineVariant,
                                                RoundedCornerShape(8.dp),
                                            )
                                        },
                                    )
                                    .clickable { selectedIconKey = iconOption.key },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    iconOption.icon,
                                    contentDescription = iconOption.label,
                                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        BottomBarSlot(
                            type = selectedType,
                            referenceId = when (selectedType) {
                                BottomBarSlotType.TODAY, BottomBarSlotType.UPCOMING, BottomBarSlotType.ANYTIME -> ""
                                else -> selectedReferenceId
                            },
                            iconKey = when (selectedType) {
                                BottomBarSlotType.TODAY, BottomBarSlotType.UPCOMING, BottomBarSlotType.ANYTIME -> ""
                                else -> selectedIconKey
                            },
                        ),
                    )
                },
                enabled = isValid,
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
