package com.rendyhd.vicu.ui.screens.anytime

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.repository.ProActions
import com.rendyhd.vicu.data.repository.ProListState
import com.rendyhd.vicu.data.repository.ProWorldSource
import com.rendyhd.vicu.ui.screens.pro.toListView
import com.rendyhd.vicu.util.pro.AvailabilityView
import com.rendyhd.vicu.util.pro.ProListKeys
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.ui.navigation.NavigationTicker
import com.rendyhd.vicu.ui.screens.shared.CompletionHold
import com.rendyhd.vicu.ui.screens.shared.CompletionToast
import com.rendyhd.vicu.ui.screens.shared.NoCompletionToast
import com.rendyhd.vicu.data.sync.ScreenRefresher
import com.rendyhd.vicu.data.sync.refreshErrorToShow
import com.rendyhd.vicu.util.NetworkResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
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
    /** Available, Remaining or All while Pro's availability layer is on (contract 21.2); null otherwise. */
    val proView: AvailabilityView? = null,
    /** Open tasks the view leaves out. */
    val proHidden: Int = 0,
)

class AnytimeViewModel(
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val labelRepository: LabelRepository,
    private val authManager: AuthManager,
    private val refresher: ScreenRefresher,
    navigationTicker: NavigationTicker = NavigationTicker(),
    completionToast: CompletionToast = NoCompletionToast,
    /** Pro's Available / Remaining / All (contract 21.2); none in tests. */
    private val proWorld: ProWorldSource? = null,
    private val proActions: ProActions? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AnytimeUiState())
    val uiState: StateFlow<AnytimeUiState> = _uiState.asStateFlow()

    /** Rows completed on this screen, kept in place for a moment (see [CompletionHold]). */
    val completions = CompletionHold(viewModelScope, navigationTicker = navigationTicker, toast = completionToast)

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
                    proWorld?.listState(ProListKeys.ANYTIME) ?: flowOf(ProListState.OFF),
                ) { storedTasks, projects, _, collapsed, pro ->
                    // With Pro off the filter keeps every task (contract 21.1, O1).
                    val shown = pro.filter(storedTasks)
                    Triple(buildAnytimeGroups(projects, completions.merge(shown.tasks), collapsed), pro.view, shown.hidden)
                }.collect { (groups, view, hidden) ->
                    _uiState.update { current ->
                        current.copy(
                            projectGroups = groups,
                            rows = flattenAnytimeGroups(groups, completions.completedIds),
                            isLoading = false,
                            proView = view,
                            proHidden = hidden,
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

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /** What the list shows while Pro's availability layer is on; per device and list. */
    fun setProView(view: AvailabilityView) {
        proActions?.setListView(ProListKeys.ANYTIME, view.toListView())
    }
}

private const val NO_PROJECT_ID = 0L
