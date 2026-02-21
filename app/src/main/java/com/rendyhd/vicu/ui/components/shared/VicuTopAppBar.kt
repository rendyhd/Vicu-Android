package com.rendyhd.vicu.ui.components.shared

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VicuTopAppBar(
    title: @Composable () -> Unit,
    onOpenDrawer: () -> Unit,
    onNavigateToSearch: () -> Unit,
) {
    TopAppBar(
        title = title,
        navigationIcon = {
            IconButton(onClick = onOpenDrawer) {
                Icon(Icons.Default.Menu, contentDescription = "Open menu")
            }
        },
        actions = {
            IconButton(onClick = onNavigateToSearch) {
                Icon(Icons.Default.Search, contentDescription = "Search")
            }
        },
    )
}
