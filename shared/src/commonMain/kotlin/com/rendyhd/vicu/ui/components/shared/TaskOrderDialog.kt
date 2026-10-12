package com.rendyhd.vicu.ui.components.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.domain.model.TaskOrder

/**
 * Picks a task order. With [inheritedOrder] set (a project's choice) the first row is "Default",
 * which follows that order and is chosen as null; without it (the global choice in Settings) only
 * the orders are listed. Choosing a row closes the dialog.
 */
@Composable
fun TaskOrderDialog(
    title: String,
    selected: TaskOrder?,
    onSelect: (TaskOrder?) -> Unit,
    onDismiss: () -> Unit,
    inheritedOrder: TaskOrder? = null,
    note: String? = "Tasks can be dragged in Manual, and undated ones in Due date, then manual.",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup()) {
                if (inheritedOrder != null) {
                    OrderRow(
                        label = "Default (${inheritedOrder.label})",
                        selected = selected == null,
                        onClick = { onSelect(null) },
                    )
                }
                TaskOrder.entries.forEach { order ->
                    OrderRow(
                        label = order.label,
                        selected = selected == order,
                        onClick = { onSelect(order) },
                    )
                }
                if (note != null) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun OrderRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(label)
    }
}
