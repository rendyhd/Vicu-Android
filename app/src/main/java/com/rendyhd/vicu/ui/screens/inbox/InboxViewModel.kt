package com.rendyhd.vicu.ui.screens.inbox

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.auth.AuthManager
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

data class InboxUiState(
    val tasks: List<Task> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val completedTaskIds: Set<Long> = emptySet(),
    val inboxProjectId: Long? = null,
)

@HiltViewModel
class InboxViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val labelRepository: LabelRepository,
    private val authManager: AuthManager,
) : ViewModel() {

    companion object {
        private const val TAG = "InboxViewModel"
    }

    private val _uiState = MutableStateFlow(InboxUiState())
    val uiState: StateFlow<InboxUiState> = _uiState.asStateFlow()

    init {
        Log.d(TAG, "init: InboxViewModel created")
        viewModelScope.launch {
            val inboxId = authManager.getInboxProjectId()
            Log.d(TAG, "init: inboxProjectId=$inboxId")
            _uiState.update { it.copy(inboxProjectId = inboxId) }
            if (inboxId == null) {
                Log.e(TAG, "init: inboxProjectId is NULL — Flow collection skipped!")
                return@launch
            }
            taskRepository.getInboxTasks(inboxId).collect { tasks ->
                Log.d(TAG, "Flow emission: ${tasks.size} tasks for inboxId=$inboxId")
                _uiState.update { it.copy(tasks = tasks, isLoading = false) }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            Log.d(TAG, "refresh() starting")
            val completedIds = _uiState.value.completedTaskIds
            _uiState.update { it.copy(isRefreshing = true, error = null, completedTaskIds = emptySet()) }
            if (completedIds.isNotEmpty()) taskRepository.deleteLocalByIds(completedIds)
            val taskResult = taskRepository.refreshAll()
            Log.d(TAG, "refresh() taskRepository.refreshAll() = $taskResult")
            val projResult = projectRepository.refreshAll()
            Log.d(TAG, "refresh() projectRepository.refreshAll() = $projResult")
            val labelResult = labelRepository.refreshAll()
            Log.d(TAG, "refresh() labelRepository.refreshAll() = $labelResult")
            _uiState.update { it.copy(isRefreshing = false) }
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
