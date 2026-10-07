package com.rendyhd.vicu.ui.screens.logbook

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.data.sync.ScreenRefresher
import com.rendyhd.vicu.data.sync.refreshErrorToShow
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
    /** Whether the server has older completed tasks than the pages loaded so far. */
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    /** Pages of completed tasks fetched so far; the screen asks for the next one when it ends. */
    val pagesLoaded: Int = 0,
)

class LogbookViewModel(
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val labelRepository: LabelRepository,
    private val refresher: ScreenRefresher,
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
        if (refresher.isStale()) {
            refresh()
        } else {
            viewModelScope.launch {
                val result = loadFirstPage()
                _uiState.update { it.copy(error = result.refreshErrorToShow(manual = false) ?: it.error) }
            }
        }
    }

    private suspend fun loadFirstPage(): NetworkResult<*> {
        val result = taskRepository.loadLogbookPage(1)
        if (result is NetworkResult.Success) {
            _uiState.update { it.copy(hasMore = result.data.hasMore, pagesLoaded = 1) }
        }
        return result
    }

    /** Fetches the next page of older completed tasks; the screen calls it when the list ends. */
    fun loadMore() {
        val current = _uiState.value
        if (!current.hasMore || current.isLoadingMore || current.pagesLoaded == 0) return
        _uiState.update { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
            val next = current.pagesLoaded + 1
            when (val result = taskRepository.loadLogbookPage(next)) {
                is NetworkResult.Success -> _uiState.update {
                    it.copy(isLoadingMore = false, hasMore = result.data.hasMore, pagesLoaded = next)
                }
                is NetworkResult.Error -> _uiState.update {
                    // The user scrolled to the end and asked for this, so say why it did not work.
                    it.copy(isLoadingMore = false, error = result.message)
                }
                else -> _uiState.update { it.copy(isLoadingMore = false) }
            }
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
            val result = refresher.refresh(manual = showSpinner)
            // The first page of completed history comes with every refresh, unless the server
            // could not be reached at all (a second attempt would only fail the same way).
            val completed = if (result is NetworkResult.Error && result.offline) null else loadFirstPage()
            val failure = if (result is NetworkResult.Error) result else completed
            _uiState.update {
                it.copy(
                    isRefreshing = false,
                    error = failure?.refreshErrorToShow(showSpinner) ?: it.error,
                )
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
