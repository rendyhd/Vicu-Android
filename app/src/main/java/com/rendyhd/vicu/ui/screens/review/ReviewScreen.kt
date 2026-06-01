package com.rendyhd.vicu.ui.screens.review

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rendyhd.vicu.util.ReviewState

private fun parseHexColor(hex: String): Color? {
    if (hex.isBlank()) return null
    return try {
        Color(android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex"))
    } catch (_: Exception) {
        null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    onOpenDrawer: () -> Unit,
    viewModel: ReviewViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.undo) {
        val prev = state.undo ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Marked \"${prev.title}\" reviewed",
            actionLabel = "Undo",
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undo() else viewModel.dismissUndo()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review") },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Open menu")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            val reviewedCount = state.reviewedThisSession.size
            val remaining = state.due.count { !state.reviewedThisSession.contains(it.project.id) }
            val total = (reviewedCount + remaining).coerceAtLeast(1)
            LinearProgressIndicator(
                progress = { reviewedCount.toFloat() / total.toFloat() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            TabRow(selectedTabIndex = if (state.tab == ReviewTab.DUE) 0 else 1) {
                Tab(
                    selected = state.tab == ReviewTab.DUE,
                    onClick = { viewModel.setTab(ReviewTab.DUE) },
                    text = { Text("Due (${state.due.size})") },
                )
                Tab(
                    selected = state.tab == ReviewTab.ALL,
                    onClick = { viewModel.setTab(ReviewTab.ALL) },
                    text = { Text("All tracked (${state.all.size})") },
                )
            }
            val items = if (state.tab == ReviewTab.DUE) state.due else state.all
            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (state.tab == ReviewTab.DUE) "You're caught up." else "No tracked projects.")
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items, key = { it.project.id }) { item ->
                        ReviewRow(
                            item = item,
                            reviewed = item.project.id in state.reviewedThisSession,
                            onMarkReviewed = { viewModel.markReviewed(item.project) },
                            onSetCadence = { days -> viewModel.setCadence(item.project, days) },
                            onExclude = { viewModel.setExcluded(item.project, true) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewRow(
    item: ReviewItem,
    reviewed: Boolean,
    onMarkReviewed: () -> Unit,
    onSetCadence: (Int?) -> Unit,
    onExclude: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(parseHexColor(item.project.hexColor) ?: Color.Gray),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.project.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = stalenessLabel(item), style = MaterialTheme.typography.bodySmall)
        }
        if (reviewed) {
            Text("✓ Reviewed")
        } else {
            OutlinedButton(onClick = onMarkReviewed) { Text("Mark reviewed") }
        }
        IconButton(onClick = { menuOpen = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "More")
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            listOf(7, 14, 30, 90).forEach { d ->
                DropdownMenuItem(
                    text = { Text("Cadence: every $d days") },
                    onClick = {
                        onSetCadence(d)
                        menuOpen = false
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Use default cadence") },
                onClick = {
                    onSetCadence(null)
                    menuOpen = false
                },
            )
            DropdownMenuItem(
                text = { Text("Exclude from review") },
                onClick = {
                    onExclude()
                    menuOpen = false
                },
            )
        }
    }
}

private fun stalenessLabel(item: ReviewItem): String {
    val s = item.status
    return when {
        s.metadata.state == ReviewState.NEVER -> "Never reviewed"
        s.daysUntilDue == null -> "Never reviewed"
        s.daysUntilDue < 0 -> "Overdue ${-s.daysUntilDue}d"
        s.daysUntilDue == 0L -> "Due today"
        else -> "Due in ${s.daysUntilDue}d"
    }
}
