package com.rendyhd.vicu.ui.components.selection

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable

/**
 * Contextual app bar shown while multi-select is active. Actions: Move, Schedule, Apply label,
 * Complete (no Delete — bulk delete is permanent and out of scope per the Beta 3 decisions).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(
    count: Int,
    onClose: () -> Unit,
    onComplete: () -> Unit,
    onMove: () -> Unit,
    onSchedule: () -> Unit,
    onApplyLabel: () -> Unit,
) {
    TopAppBar(
        title = { Text("$count selected") },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Cancel selection")
            }
        },
        actions = {
            IconButton(onClick = onSchedule) {
                Icon(Icons.Default.Schedule, contentDescription = "Schedule")
            }
            IconButton(onClick = onApplyLabel) {
                Icon(Icons.Default.Sell, contentDescription = "Apply label")
            }
            IconButton(onClick = onMove) {
                Icon(Icons.Default.DriveFileMove, contentDescription = "Move to project")
            }
            IconButton(onClick = onComplete) {
                Icon(Icons.Default.Check, contentDescription = "Complete")
            }
        },
    )
}
