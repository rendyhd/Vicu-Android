package com.rendyhd.vicu.ui.components.shared

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * FAB that shows "New Task" label when at top of list,
 * and collapses to icon-only when scrolled past the first item.
 */
@Composable
fun VicuFab(
    onClick: () -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val expanded by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0
        }
    }

    ExtendedFloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        expanded = expanded,
        icon = { Icon(Icons.Default.Add, contentDescription = "Add task") },
        text = { Text("New Task") },
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    )
}
