package com.rendyhd.vicu.ui.components.selection

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Contextual app bar shown while multi-select is active. Today and Complete stay one tap away;
 * less-frequent actions live in the overflow menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(
    count: Int,
    onClose: () -> Unit,
    onToday: () -> Unit,
    onComplete: () -> Unit,
    onSchedule: () -> Unit,
    onSetPriority: () -> Unit,
    onMove: () -> Unit,
    onApplyLabel: () -> Unit,
    onRemove: () -> Unit,
) {
    var overflowExpanded by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    TopAppBar(
        title = { Text("$count selected") },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Cancel selection")
            }
        },
        actions = {
            IconButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.Confirm); onToday() }) {
                Icon(Icons.Default.CalendarToday, contentDescription = "Today")
            }
            IconButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.Confirm); onComplete() }) {
                Icon(Icons.Default.Check, contentDescription = "Complete")
            }
            IconButton(onClick = { overflowExpanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More actions")
            }
            DropdownMenu(
                expanded = overflowExpanded,
                onDismissRequest = { overflowExpanded = false },
            ) {
                SelectionMenuItem("Schedule") {
                    overflowExpanded = false
                    onSchedule()
                }
                SelectionMenuItem("Set priority") {
                    overflowExpanded = false
                    onSetPriority()
                }
                SelectionMenuItem("Move project") {
                    overflowExpanded = false
                    onMove()
                }
                SelectionMenuItem("Apply label") {
                    overflowExpanded = false
                    onApplyLabel()
                }
                SelectionMenuItem("Remove") {
                    overflowExpanded = false
                    onRemove()
                }
            }
        },
    )
}

@Composable
private fun SelectionMenuItem(
    label: String,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
    )
}
