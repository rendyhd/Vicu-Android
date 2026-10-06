package com.rendyhd.vicu.ui.screens.logbook

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.data.sync.SyncStaleness
import com.rendyhd.vicu.ui.screens.shared.CompletionHold
import com.rendyhd.vicu.util.NetworkResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LogbookUiState(
    val tasks: List<Task> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val uncompletedTaskIds: Set<Long> = emptySet(),
)

class LogbookViewModel(
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val labelRepository: LabelRepository,
    private val syncStaleness: SyncStaleness,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LogbookUiState())
    val uiState: StateFlow<LogbookUiState> = _uiState.asStateFlow()

    /**
     * Rows reopened on this screen. Reopening changes the stored task at once, which takes it
     * out of the Logbook; the hold keeps it in place, drawn as open, so it can be completed again.
     */
    val completions = CompletionHold(viewModelScope)

    init {
        viewModelScope.launch {
            completions.heldIds.collect { ids -> _uiState.update { it.copy(uncompletedTaskIds = ids) } }
        }
        viewModelScope.launch {
            combine(
                taskRepository.getLogbookTasks(),
                projectRepository.getAll(),
                completions.state,
            ) { tasks, projects, _ ->
                val activeIds = projects.mapTo(mutableSetOf()) { it.id }
                completions.merge(tasks.filter { it.projectId in activeIds })
            }.collect { visibleTasks ->
                _uiState.update { it.copy(tasks = visibleTasks, isLoading = false) }
            }
        }
        // Completed history is not part of the normal sync: the first page is fetched whenever
        // the screen opens (and on pull-to-refresh). The rest of a refresh runs when stale.
        if (syncStaleness.isStale()) {
            refresh()
        } else {
            viewModelScope.launch { taskRepository.loadLogbookPage(1) }
        }
    }

    fun toggleDone(task: Task) {
        viewModelScope.launch {
            // Reopening takes the row out of the Logbook at once; the hold keeps it in place.
            if (task.done) completions.hold(task)
            when (val result = taskRepository.toggleDone(task)) {
                is NetworkResult.Error -> {
                    // Hard failure: put the row back to normal along with surfacing the error.
                    completions.release(task.id)
                    _uiState.update { it.copy(error = result.message) }
                }
                else -> {}
            }
        }
    }

    /** Undo of a reopen: complete the task again (not another toggle, which would reopen it twice). */
    fun undoUncomplete(task: Task) {
        viewModelScope.launch {
            completions.undoing(task.id)
            val result = taskRepository.setDone(task.id, true)
            completions.release(task.id)
            if (result is NetworkResult.Error) _uiState.update { it.copy(error = result.message) }
        }
    }

    fun refresh(showSpinner: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = showSpinner, error = null) }
            try {
                val tasks = taskRepository.refreshAll()
                val projects = projectRepository.refreshAll()
                labelRepository.refreshAll()
                val completed = taskRepository.loadLogbookPage(1)
                val failure = listOf(tasks, projects, completed).filterIsInstance<NetworkResult.Error>().firstOrNull()
                if (failure != null) {
                    _uiState.update { it.copy(error = failure.message) }
                } else {
                    syncStaleness.markSynced()
                }
            } catch (e: Exception) {
                Log.e("LogbookViewModel", "refresh() failed: ${e.message}", e)
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
