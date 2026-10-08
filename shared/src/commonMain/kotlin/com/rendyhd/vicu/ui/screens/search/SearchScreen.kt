package com.rendyhd.vicu.ui.screens.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.ui.components.shared.FabClearance
import org.koin.compose.viewmodel.koinViewModel
import com.rendyhd.vicu.ui.components.section.SectionHeader
import com.rendyhd.vicu.ui.components.shared.EmptyState
import com.rendyhd.vicu.ui.components.task.TaskItem
import com.rendyhd.vicu.ui.components.task.RowView
import com.rendyhd.vicu.ui.components.task.projectMetas

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onTaskClick: (Long) -> Unit = {},
    onNavigateBack: () -> Unit = {},
    viewModel: SearchViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val rowView = remember(projects) { RowView(projects = projectMetas(projects)) }

    // Rows kept on screen after completing them are let go when the screen is left.
    val snackbarHostState = remember { SnackbarHostState() }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        // Search bar
        TextField(
            value = state.query,
            onValueChange = viewModel::onQueryChanged,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            placeholder = { Text("Search tasks...") },
            leadingIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
            },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onQueryChanged("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )

        SnackbarHost(snackbarHostState)

        // The server is being asked in the background; the cached matches are already on screen.
        if (state.isRefreshing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(top = 4.dp),
            contentPadding = PaddingValues(bottom = FabClearance),
        ) {
            if (state.query.isBlank()) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.Search,
                        title = "Search tasks",
                        subtitle = "Type to search titles and notes",
                    )
                }
            } else if (state.results.isEmpty() && state.completedResults.isEmpty()) {
                // Nothing is said about "no results" until the cached matches were read and the
                // server had its say; an empty list next to a progress bar is not an answer yet.
                if (state.resultsReady && !state.isRefreshing) item {
                    EmptyState(
                        icon = Icons.Outlined.SearchOff,
                        title = "No results",
                        subtitle = "Try a different search term",
                    )
                }
            } else {
                items(state.results, key = { it.id }, contentType = { "task" }) { task ->
                    TaskItem(
                        task = if (task.id in state.completedTaskIds) task.copy(done = true) else task,
                        onToggleDone = {
                            if (task.id in state.completedTaskIds) {
                                viewModel.undoComplete(task)
                            } else {
                                viewModel.toggleDone(task)
                            }
                        },
                        onClick = { onTaskClick(task.id) },
                        onSubtaskToggleDone = viewModel::toggleDone,
                        onSubtaskClick = { child -> onTaskClick(child.id) },
                        rowView = rowView,
                        modifier = Modifier.animateItem(),
                    )
                }
                if (state.completedResults.isNotEmpty()) {
                    item(key = "completed_header", contentType = "header") {
                        SectionHeader(title = "Completed")
                    }
                    items(state.completedResults, key = { it.id }, contentType = { "task" }) { task ->
                        TaskItem(
                            task = task,
                            onToggleDone = { viewModel.toggleDone(task) },
                            onClick = { onTaskClick(task.id) },
                            onSubtaskToggleDone = viewModel::toggleDone,
                            onSubtaskClick = { child -> onTaskClick(child.id) },
                            rowView = rowView,
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(state.error) {
        val error = state.error ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = error,
            actionLabel = "Dismiss",
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed || result == SnackbarResult.Dismissed) {
            viewModel.clearError()
        }
    }

}
