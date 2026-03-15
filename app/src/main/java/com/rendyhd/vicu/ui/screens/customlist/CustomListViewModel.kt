package com.rendyhd.vicu.ui.screens.customlist

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.local.CustomListStore
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.util.CustomListFilterBuilder
import com.rendyhd.vicu.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CustomListUiState(
    val customList: CustomList? = null,
    val tasks: List<Task> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val completedTaskIds: Set<Long> = emptySet(),
    val inboxProjectId: Long = 0L,
)

@HiltViewModel
class CustomListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val labelRepository: LabelRepository,
    private val customListStore: CustomListStore,
    private val authManager: AuthManager,
) : ViewModel() {

    private val listId: String = savedStateHandle["listId"]!!

    private val _uiState = MutableStateFlow(CustomListUiState())
    val uiState: StateFlow<CustomListUiState> = _uiState.asStateFlow()

    val projects: StateFlow<List<Project>> = projectRepository.getAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val labels: StateFlow<List<Label>> = labelRepository.getAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        viewModelScope.launch {
            customListStore.getById(listId).collect { customList ->
                _uiState.update { it.copy(customList = customList) }
                if (customList != null) {
                    loadTasks(customList)
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
        viewModelScope.launch {
            val inboxId = authManager.getInboxProjectId() ?: 0L
            _uiState.update { it.copy(inboxProjectId = inboxId) }
        }
    }

    private fun loadTasks(customList: CustomList) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val params = CustomListFilterBuilder.buildQueryParams(customList.filter)
            taskRepository.refreshAll(params)

            // Observe all tasks and apply client-side filters
            taskRepository.getAllOpenTasks().collect { tasks ->
                val allTasks = if (customList.filter.includeDone) {
                    tasks // getAllOpenTasks only returns non-done; for includeDone we'd need a broader query
                } else {
                    tasks
                }
                val filtered = CustomListFilterBuilder.applyClientSideFilters(allTasks, customList.filter)
                _uiState.update { it.copy(tasks = filtered, isLoading = false) }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val completedIds = _uiState.value.completedTaskIds
            _uiState.update { it.copy(isRefreshing = true, error = null, completedTaskIds = emptySet()) }
            try {
                if (completedIds.isNotEmpty()) taskRepository.deleteLocalByIds(completedIds)
                val customList = _uiState.value.customList
                if (customList != null) {
                    val params = CustomListFilterBuilder.buildQueryParams(customList.filter)
                    taskRepository.refreshAll(params)
                }
                projectRepository.refreshAll()
                labelRepository.refreshAll()
            } catch (e: Exception) {
                Log.e("CustomListViewModel", "refresh() failed: ${e.message}", e)
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
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

    fun rescheduleTask(task: Task, newDueDate: String) {
        viewModelScope.launch {
            val updated = task.copy(dueDate = newDueDate)
            taskRepository.update(updated)
        }
    }

    fun saveCustomList(customList: CustomList) {
        viewModelScope.launch {
            customListStore.save(customList)
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
