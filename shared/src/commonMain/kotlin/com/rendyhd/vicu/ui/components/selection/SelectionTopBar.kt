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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rendyhd.vicu.ui.components.collab.BulkAssign
import com.rendyhd.vicu.ui.components.collab.LocalCollab
import com.rendyhd.vicu.ui.components.shared.copyPlainText
import kotlinx.coroutines.launch

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
    /** Assign, only while every selected task is in a shared project you may write (semantics 9.2). */
    onAssign: (() -> Unit)? = null,
    onDuplicate: (() -> Unit)? = null,
    /** The selected tasks' titles for Copy titles, one per line on the clipboard (semantics 15). */
    copyTitles: (suspend () -> List<String>)? = null,
) {
    var overflowExpanded by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

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
                if (onAssign != null) {
                    SelectionMenuItem("Assign") {
                        overflowExpanded = false
                        onAssign()
                    }
                }
                if (onDuplicate != null) {
                    SelectionMenuItem("Duplicate") {
                        overflowExpanded = false
                        onDuplicate()
                    }
                }
                if (copyTitles != null) {
                    SelectionMenuItem(if (count == 1) "Copy title" else "Copy titles") {
                        overflowExpanded = false
                        scope.launch {
                            val titles = copyTitles()
                            if (titles.isNotEmpty()) clipboard.copyPlainText("Task titles", titles.joinToString("\n"))
                        }
                    }
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

/** Assign in the selection's menu while it can be used (cross-app semantics 9.2), else null. */
@Composable
fun SelectionViewModel.assignAction(open: () -> Unit): (() -> Unit)? {
    val projectIds by selectedProjectIds.collectAsStateWithLifecycle()
    return if (BulkAssign.canAssign(projectIds, LocalCollab.current.state)) open else null
}
