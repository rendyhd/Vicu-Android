package com.rendyhd.vicu.ui.screens.anytime

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.ui.screens.shared.CompletionHold
import com.rendyhd.vicu.data.sync.ScreenRefresher
import com.rendyhd.vicu.data.sync.refreshErrorToShow
import com.rendyhd.vicu.util.NetworkResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AnytimeUiState(
    val projectGroups: List<AnytimeProjectGroup> = emptyList(),
    /** [projectGroups] flattened for the lazy list: headers and tasks, depth first. */
    val rows: List<AnytimeRow> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val completedTaskIds: Set<Long> = emptySet(),
)

class AnytimeViewModel(
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val labelRepository: LabelRepository,
    private val authManager: AuthManager,
    private val refresher: ScreenRefresher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AnytimeUiState())
    val uiState: StateFlow<AnytimeUiState> = _uiState.asStateFlow()

    /** Rows completed on this screen, kept in place for a moment (see [CompletionHold]). */
    val completions = CompletionHold(viewModelScope)

    /** Projects the user collapsed, by id: a position in the list changes whenever the data does. */
    private val collapsedProjectIds = MutableStateFlow<Set<Long>>(emptySet())

    init {
        viewModelScope.launch {
            completions.heldIds.collect { ids -> _uiState.update { it.copy(completedTaskIds = ids) } }
        }
        viewModelScope.launch {
            authManager.inboxProjectId.collectLatest { inboxId ->
                // With no Inbox chosen yet nothing is left out: no project has id 0.
                combine(
                    taskRepository.getAnytimeTasks(inboxId ?: NO_PROJECT_ID),
                    projectRepository.getAll(),
                    completions.state,
                    collapsedProjectIds,
                ) { storedTasks, projects, _, collapsed ->
                    buildAnytimeGroups(projects, completions.merge(storedTasks), collapsed)
                }.collect { groups ->
                    _uiState.update { current ->
                        current.copy(
                            projectGroups = groups,
                            rows = flattenAnytimeGroups(groups),
                            isLoading = false,
                        )
                    }
                }
            }
        }
        if (refresher.isStale()) refresh()
    }

    /** Collapses or expands the project with [projectId], wherever it sits in the tree. */
    fun toggleProject(projectId: Long) {
        collapsedProjectIds.update { if (projectId in it) it - projectId else it + projectId }
    }

    fun refresh(showSpinner: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = showSpinner, error = null) }
            val result = refresher.refresh(manual = showSpinner)
            // A failed refresh is shown (an offline one only when the user asked for it) and
            // leaves the app stale, so the next screen tries again.
            _uiState.update { it.copy(isRefreshing = false, error = result.refreshErrorToShow(showSpinner) ?: it.error) }
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

private const val NO_PROJECT_ID = 0L
