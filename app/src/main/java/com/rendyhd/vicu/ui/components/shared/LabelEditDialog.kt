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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.domain.model.Label

val PRESET_COLORS = listOf(
    "#e8384f", "#fd612c", "#fd9a00", "#eec300",
    "#a4cf30", "#37c5ab", "#20aaea", "#4186e0",
    "#7a6ff0", "#aa62e3",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LabelEditDialog(
    label: Label? = null,
    onSave: (name: String, hexColor: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val isEdit = label != null
    var name by remember { mutableStateOf(label?.title ?: "") }
    var selectedColor by remember {
        mutableStateOf(
            label?.hexColor?.ifBlank { null } ?: PRESET_COLORS.first()
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "Edit Label" else "Create Label") },
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
                        onSave(name.trim(), selectedColor)
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
