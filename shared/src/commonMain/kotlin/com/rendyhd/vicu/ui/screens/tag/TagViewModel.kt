package com.rendyhd.vicu.ui.screens.tag

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.data.repository.ProActions
import com.rendyhd.vicu.data.repository.ProListState
import com.rendyhd.vicu.data.repository.ProWorldSource
import com.rendyhd.vicu.ui.screens.pro.toListView
import com.rendyhd.vicu.util.pro.AvailabilityView
import com.rendyhd.vicu.util.pro.ProListKeys
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.ui.navigation.NavigationTicker
import com.rendyhd.vicu.ui.screens.shared.CompletionHold
import com.rendyhd.vicu.ui.screens.shared.CompletionToast
import com.rendyhd.vicu.ui.screens.shared.NoCompletionToast
import com.rendyhd.vicu.ui.screens.shared.TaskProjectGroup
import com.rendyhd.vicu.ui.screens.shared.buildTaskProjectGroups
import com.rendyhd.vicu.data.sync.ScreenRefresher
import com.rendyhd.vicu.data.sync.refreshErrorToShow
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.withoutNestedSubtasks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TagUiState(
    val label: Label? = null,
    val tasks: List<Task> = emptyList(),
    /** [tasks] grouped by project, the way the desktop Tag view shows them. */
    val groups: List<TaskProjectGroup> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val completedTaskIds: Set<Long> = emptySet(),
    /** Available, Remaining or All while Pro's availability layer is on (contract 21.2); null otherwise. */
    val proView: AvailabilityView? = null,
    /** Open tasks the view leaves out. */
    val proHidden: Int = 0,
)

class TagViewModel(
    savedStateHandle: SavedStateHandle,
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val labelRepository: LabelRepository,
    private val refresher: ScreenRefresher,
    navigationTicker: NavigationTicker = NavigationTicker(),
    completionToast: CompletionToast = NoCompletionToast,
    /** Pro's Available / Remaining / All (contract 21.2); none in tests. */
    private val proWorld: ProWorldSource? = null,
    private val proActions: ProActions? = null,
) : ViewModel() {

    private val labelId: Long = savedStateHandle["labelId"]!!

    private val _uiState = MutableStateFlow(TagUiState())
    val uiState: StateFlow<TagUiState> = _uiState.asStateFlow()

    /** Rows completed on this screen, kept in place for a moment (see [CompletionHold]). */
    val completions = CompletionHold(viewModelScope, navigationTicker = navigationTicker, toast = completionToast)

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
                proWorld?.listState(ProListKeys.tag(labelId)) ?: flowOf(ProListState.OFF),
            ) { tasks, projects, _, pro ->
                val activeIds = projects.mapTo(mutableSetOf()) { it.id }
                // Filter first, then hide nested subtasks among the matches: a labeled subtask
                // shows even when its parent does not carry the label (X-16).
                val filtered = tasks
                    .filter { task -> task.projectId in activeIds && task.labels.any { it.id == labelId } }
                    .withoutNestedSubtasks(hideChildrenOfCompletedParents = false)
                // With Pro off the filter keeps every task (contract 21.1, O1).
                val viewed = pro.filter(filtered)
                val shown = completions.merge(viewed.tasks)
                TagLists(shown, buildTaskProjectGroups(shown, projects, inboxId = null), pro.view, viewed.hidden)
            }.collect { lists ->
                _uiState.update {
                    it.copy(tasks = lists.shown, groups = lists.groups, isLoading = false, proView = lists.view, proHidden = lists.hidden)
                }
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

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /** What the list shows while Pro's availability layer is on; per device and list. */
    fun setProView(view: AvailabilityView) {
        proActions?.setListView(ProListKeys.tag(labelId), view.toListView())
    }
}

private class TagLists(
    val shown: List<Task>,
    val groups: List<TaskProjectGroup>,
    val view: AvailabilityView?,
    val hidden: Int,
)
