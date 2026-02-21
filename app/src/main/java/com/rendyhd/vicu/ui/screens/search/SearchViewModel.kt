package com.rendyhd.vicu.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val results: List<Task> = emptyList(),
    val isSearching: Boolean = false,
    val error: String? = null,
    val completedTaskIds: Set<Long> = emptySet(),
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val labelRepository: LabelRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var collectJob: Job? = null

    fun onQueryChanged(query: String) {
        _uiState.update { it.copy(query = query) }

        searchJob?.cancel()

        if (query.isBlank()) {
            collectJob?.cancel()
            _uiState.update { it.copy(results = emptyList(), isSearching = false) }
            return
        }

        searchJob = viewModelScope.launch {
            delay(300) // debounce
            _uiState.update { it.copy(isSearching = true) }

            // Trigger API search to refresh local cache
            taskRepository.refreshAll(mapOf("s" to query))

            // Observe local results
            collectJob?.cancel()
            collectJob = viewModelScope.launch {
                taskRepository.searchByTitle(query).collect { tasks ->
                    _uiState.update { it.copy(results = tasks, isSearching = false) }
                }
            }
        }
    }

    fun toggleDone(task: Task) {
        viewModelScope.launch {
            if (!task.done) {
                _uiState.update { it.copy(completedTaskIds = it.completedTaskIds + task.id) }
            }
            when (val result = taskRepository.toggleDone(task)) {
                is NetworkResult.Error -> {
                    _uiState.update { it.copy(error = result.message) }
                }
                else -> {}
            }
        }
    }

    fun undoComplete(task: Task) {
        viewModelScope.launch {
            _uiState.update { it.copy(completedTaskIds = it.completedTaskIds - task.id) }
            taskRepository.toggleDone(task)
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
