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
import com.rendyhd.vicu.ui.theme.PriorityHigh
import com.rendyhd.vicu.ui.theme.PriorityLow
import com.rendyhd.vicu.ui.theme.PriorityMedium
import com.rendyhd.vicu.ui.theme.PriorityUrgent

private data class PriorityOption(val value: Int, val label: String, val color: Color?)

@Composable
fun PriorityPickerDialog(
    current: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(
        PriorityOption(0, "None", null),
        PriorityOption(1, "Low", PriorityLow),
        PriorityOption(2, "Medium", PriorityMedium),
        PriorityOption(3, "High", PriorityHigh),
        PriorityOption(4, "Urgent", PriorityUrgent),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Priority") },
        text = {
            LazyColumn {
                items(options, key = { it.value }) { option ->
                    val isSelected = option.value == current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onPick(option.value)
                                onDismiss()
                            }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = option.color ?: MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            modifier = Modifier.size(10.dp),
                        ) {}

                        Text(
                            text = option.label,
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
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
