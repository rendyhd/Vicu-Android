package com.rendyhd.vicu.ui.screens.logbook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LogbookUiState(
    val tasks: List<Task> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val uncompletedTaskIds: Set<Long> = emptySet(),
)

@HiltViewModel
class LogbookViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val labelRepository: LabelRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LogbookUiState())
    val uiState: StateFlow<LogbookUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            taskRepository.getLogbookTasks().collect { tasks ->
                _uiState.update { it.copy(tasks = tasks, isLoading = false) }
            }
        }
        refresh()
    }

    fun toggleDone(task: Task) {
        viewModelScope.launch {
            if (task.done) {
                _uiState.update { it.copy(uncompletedTaskIds = it.uncompletedTaskIds + task.id) }
            }
            when (val result = taskRepository.toggleDone(task)) {
                is NetworkResult.Error -> {
                    _uiState.update { it.copy(error = result.message) }
                }
                else -> {}
            }
        }
    }

    fun undoUncomplete(task: Task) {
        viewModelScope.launch {
            _uiState.update { it.copy(uncompletedTaskIds = it.uncompletedTaskIds - task.id) }
            taskRepository.toggleDone(task)
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val uncompletedIds = _uiState.value.uncompletedTaskIds
            _uiState.update { it.copy(isRefreshing = true, error = null, uncompletedTaskIds = emptySet()) }
            if (uncompletedIds.isNotEmpty()) taskRepository.deleteLocalByIds(uncompletedIds)
            taskRepository.refreshAll(mapOf("filter" to "done = true"))
            projectRepository.refreshAll()
            labelRepository.refreshAll()
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
