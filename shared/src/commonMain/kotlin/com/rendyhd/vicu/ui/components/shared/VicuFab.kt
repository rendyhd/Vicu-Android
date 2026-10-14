package com.rendyhd.vicu.ui.components.shared

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * The FAB for creating something new, an extended button with a "New task" label (or another
 * [label]). A list passes [expanded] = false while it is scrolled, and the button shrinks to its
 * icon; the button keeps the same name for a screen reader in both states.
 */
@Composable
fun VicuFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "New task",
    expanded: Boolean = true,
) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        // One name in both states, the same words as the visible label.
        modifier = modifier.semantics { contentDescription = label },
        expanded = expanded,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        icon = { Icon(Icons.Default.Add, contentDescription = null) },
        text = { Text(label) },
    )
}
