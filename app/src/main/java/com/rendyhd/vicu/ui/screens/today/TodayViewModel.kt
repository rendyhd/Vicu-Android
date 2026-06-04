package com.rendyhd.vicu.ui.screens.today

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.ui.screens.shared.TaskProjectGroup
import com.rendyhd.vicu.ui.screens.shared.buildTaskProjectGroups
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.data.sync.SyncStaleness
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TodayUiState(
    val projectGroups: List<TaskProjectGroup> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val completedTaskIds: Set<Long> = emptySet(),
)

@HiltViewModel
class TodayViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val labelRepository: LabelRepository,
    private val authManager: AuthManager,
    private val syncStaleness: SyncStaleness,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TodayUiState())
    val uiState: StateFlow<TodayUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val inboxId = authManager.getInboxProjectId()
            combine(
                taskRepository.getTodayTasks(),
                projectRepository.getAll(),
            ) { tasks, projects ->
                buildTaskProjectGroups(tasks, projects, inboxId)
            }.collect { groups ->
                _uiState.update { current ->
                    // Preserve per-project expansion across refreshes.
                    val merged = groups.map { g ->
                        g.copy(
                            isExpanded = current.projectGroups
                                .find { it.projectId == g.projectId }?.isExpanded ?: true,
                        )
                    }
                    current.copy(projectGroups = merged, isLoading = false)
                }
            }
        }
        if (syncStaleness.isStale()) refresh()
    }

    fun toggleProject(projectId: Long) {
        _uiState.update { state ->
            state.copy(
                projectGroups = state.projectGroups.map {
                    if (it.projectId == projectId) it.copy(isExpanded = !it.isExpanded) else it
                },
            )
        }
    }

    fun refresh(showSpinner: Boolean = false) {
        viewModelScope.launch {
            val completedIds = _uiState.value.completedTaskIds
            _uiState.update { it.copy(isRefreshing = showSpinner, error = null, completedTaskIds = emptySet()) }
            try {
                if (completedIds.isNotEmpty()) taskRepository.deleteLocalByIds(completedIds)
                taskRepository.refreshAll()
                projectRepository.refreshAll()
                labelRepository.refreshAll()
                syncStaleness.markSynced()
            } catch (e: Exception) {
                Log.e("TodayViewModel", "refresh() failed: ${e.message}", e)
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
            // The row still renders done=false (Room is never flipped on complete — the
            // strikethrough is driven by completedTaskIds), and toggleDone flips whatever
            // it's handed. Pass done=true so it reverts to done=false remotely; passing the
            // raw task would re-send done=true and the completion would survive a refresh.
            taskRepository.toggleDone(task.copy(done = true))
        }
    }

    /** Swipe-schedule: applies the configured Today/Urgent action via the repository. */
    fun scheduleTask(task: Task) {
        viewModelScope.launch {
            taskRepository.applyScheduleAction(task)
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
