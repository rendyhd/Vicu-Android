package com.rendyhd.vicu.ui.screens.tag

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.ui.screens.shared.CompletionHold
import com.rendyhd.vicu.data.sync.ScreenRefresher
import com.rendyhd.vicu.data.sync.refreshErrorToShow
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.withoutNestedSubtasks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TagUiState(
    val label: Label? = null,
    val tasks: List<Task> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val completedTaskIds: Set<Long> = emptySet(),
)

class TagViewModel(
    savedStateHandle: SavedStateHandle,
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val labelRepository: LabelRepository,
    private val refresher: ScreenRefresher,
) : ViewModel() {

    private val labelId: Long = savedStateHandle["labelId"]!!

    private val _uiState = MutableStateFlow(TagUiState())
    val uiState: StateFlow<TagUiState> = _uiState.asStateFlow()

    /** Rows completed on this screen, kept in place for a moment (see [CompletionHold]). */
    val completions = CompletionHold(viewModelScope)

    init {
        viewModelScope.launch {
            completions.heldIds.collect { ids -> _uiState.update { it.copy(completedTaskIds = ids) } }
        }
        viewModelScope.launch {
            val label = labelRepository.getById(labelId)
            _uiState.update { it.copy(label = label) }
        }
        viewModelScope.launch {
            combine(
                taskRepository.getAllOpenTasksFlat(),
                projectRepository.getAll(),
                completions.state,
            ) { tasks, projects, _ ->
                val activeIds = projects.mapTo(mutableSetOf()) { it.id }
                // Filter first, then hide nested subtasks among the matches: a labeled subtask
                // shows even when its parent does not carry the label (X-16).
                val filtered = tasks
                    .filter { task -> task.projectId in activeIds && task.labels.any { it.id == labelId } }
                    .withoutNestedSubtasks(hideChildrenOfCompletedParents = false)
                completions.merge(filtered)
            }.collect { filtered ->
                _uiState.update { it.copy(tasks = filtered, isLoading = false) }
            }
        }
        if (refresher.isStale()) refresh()
    }

    fun refresh(showSpinner: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = showSpinner, error = null) }
            val result = refresher.refresh(manual = showSpinner)
            // Re-fetch the label in case it was updated
            val label = labelRepository.getById(labelId)
            _uiState.update {
                it.copy(
                    label = label,
                    isRefreshing = false,
                    error = result.refreshErrorToShow(showSpinner) ?: it.error,
                )
            }
        }
    }

    fun toggleDone(task: Task) {
        viewModelScope.launch {
            // The stored task changes at once; the hold keeps the row on screen, struck through.
            if (!task.done) completions.hold(task)
            when (val result = taskRepository.toggleDone(task)) {
                is NetworkResult.Error -> {
                    // Hard failure: put the row back to normal along with surfacing the error,
                    // otherwise it stays struck through.
                    completions.release(task.id)
                    _uiState.update { it.copy(error = result.message) }
                }
                else -> {}
            }
        }
    }

    fun undoComplete(task: Task) {
        viewModelScope.launch {
            // Draw the row as open at once but keep it in place until the task is stored as
            // open again. setDone is idempotent: it reopens the task whatever state it is in now.
            completions.undoing(task.id)
            val result = taskRepository.setDone(task.id, false)
            completions.release(task.id)
            if (result is NetworkResult.Error) _uiState.update { it.copy(error = result.message) }
        }
    }

    /** Swipe-schedule: applies the configured Today/Urgent action via the repository. */
    fun scheduleTask(taskId: Long) {
        viewModelScope.launch {
            taskRepository.applyScheduleAction(taskId)
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
