package com.rendyhd.vicu.ui.screens.tag

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.domain.model.Label
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

data class TagUiState(
    val label: Label? = null,
    val tasks: List<Task> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val completedTaskIds: Set<Long> = emptySet(),
)

@HiltViewModel
class TagViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val labelRepository: LabelRepository,
) : ViewModel() {

    private val labelId: Long = savedStateHandle["labelId"]!!

    private val _uiState = MutableStateFlow(TagUiState())
    val uiState: StateFlow<TagUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val label = labelRepository.getById(labelId)
            _uiState.update { it.copy(label = label) }
        }
        viewModelScope.launch {
            taskRepository.getAllOpenTasks().collect { tasks ->
                val filtered = tasks.filter { task ->
                    task.labels.any { it.id == labelId }
                }
                _uiState.update { it.copy(tasks = filtered, isLoading = false) }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val completedIds = _uiState.value.completedTaskIds
            _uiState.update { it.copy(isRefreshing = true, error = null, completedTaskIds = emptySet()) }
            if (completedIds.isNotEmpty()) taskRepository.deleteLocalByIds(completedIds)
            taskRepository.refreshAll()
            projectRepository.refreshAll()
            labelRepository.refreshAll()
            // Re-fetch label in case it was updated
            val label = labelRepository.getById(labelId)
            _uiState.update { it.copy(label = label, isRefreshing = false) }
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

    fun rescheduleTask(task: Task, newDueDate: String) {
        viewModelScope.launch {
            val updated = task.copy(dueDate = newDueDate)
            taskRepository.update(updated)
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
