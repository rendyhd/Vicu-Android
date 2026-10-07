package com.rendyhd.vicu.ui.components.shared

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Plus-only FAB for creating something new (a task unless [contentDescription] says otherwise).
 * Minimal and consistent across all list screens.
 */
@Composable
fun VicuFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = "Add task",
) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Icon(Icons.Default.Add, contentDescription = contentDescription)
    }
}
