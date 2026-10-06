package com.rendyhd.vicu.ui.screens.customlist

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.ui.screens.shared.CompletionHold
import com.rendyhd.vicu.domain.repository.CustomListRepository
import com.rendyhd.vicu.util.CustomListFilterBuilder
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.withoutNestedSubtasks
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CustomListUiState(
    val customList: CustomList? = null,
    val tasks: List<Task> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isDeleted: Boolean = false,
    val error: String? = null,
    val completedTaskIds: Set<Long> = emptySet(),
    val inboxProjectId: Long = 0L,
)

@OptIn(ExperimentalCoroutinesApi::class)
class CustomListViewModel(
    savedStateHandle: SavedStateHandle,
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val labelRepository: LabelRepository,
    private val customListRepository: CustomListRepository,
    private val authManager: AuthManager,
    private val dayClock: DayClock,
) : ViewModel() {

    private val listId: String = savedStateHandle["listId"]!!

    private val _uiState = MutableStateFlow(CustomListUiState())
    val uiState: StateFlow<CustomListUiState> = _uiState.asStateFlow()

    /** Rows completed on this screen, kept in place for a moment (see [CompletionHold]). */
    val completions = CompletionHold(viewModelScope)

    val projects: StateFlow<List<Project>> = projectRepository.getAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val labels: StateFlow<List<Label>> = labelRepository.getAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        viewModelScope.launch {
            completions.heldIds.collect { ids -> _uiState.update { it.copy(completedTaskIds = ids) } }
        }
        // Render from Room with client-side filter + sort. flatMapLatest cancels the previous
        // collector when the list config changes (the old code leaked one collector per edit).
        viewModelScope.launch {
            customListRepository.lists.map { lists -> lists.find { it.id == listId } }
                .flatMapLatest { customList ->
                    if (customList == null) {
                        flowOf<Pair<CustomList?, List<Task>>>(null to emptyList())
                    } else {
                        // Flat: the list's conditions apply to every task, and nested subtasks are
                        // hidden afterwards among the matches (a matching subtask shows even when
                        // its parent does not match).
                        val source = if (customList.filter.includeDone) {
                            taskRepository.getAllTasksFlat()
                        } else {
                            taskRepository.getAllOpenTasksFlat()
                        }
                        // The windows follow the local day, so the list is re-evaluated at midnight and
                        // when the time zone changes.
                        combine(source, projectRepository.getAll(), completions.state, dayClock.day) { tasks, projects, _, day ->
                            val activeIds = projects.mapTo(mutableSetOf()) { it.id }
                            val filtered = CustomListFilterBuilder.applyClientSideFilters(tasks, customList.filter, day.date, day.zone)
                                .filter { it.projectId in activeIds }
                                .withoutNestedSubtasks(hideChildrenOfCompletedParents = false)
                            customList to completions.merge(
                                CustomListFilterBuilder.sortTasks(
                                    filtered,
                                    customList.filter.sortBy,
                                    customList.filter.orderBy,
                                ),
                            )
                        }
                    }
                }
                .collect { (customList, tasks) ->
                    _uiState.update { it.copy(customList = customList, tasks = tasks, isLoading = false) }
                }
        }
        // Background network refresh, once per distinct filter config and local day (Room paints
        // first). The server filter is built from the local day's boundaries.
        viewModelScope.launch {
            combine(
                customListRepository.lists.map { lists -> lists.find { it.id == listId }?.filter },
                dayClock.day,
            ) { filter, day -> filter to day }
                .distinctUntilChanged()
                .collect { (filter, day) ->
                    if (filter != null) {
                        taskRepository.refreshAll(CustomListFilterBuilder.buildQueryParams(filter, day.date, day.zone))
                    }
                }
        }
        viewModelScope.launch {
            val inboxId = authManager.getInboxProjectId() ?: 0L
            _uiState.update { it.copy(inboxProjectId = inboxId) }
        }
    }

    fun refresh(showSpinner: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = showSpinner, error = null) }
            try {
                // A foreground refresh also pulls custom-list edits made by another client.
                customListRepository.sync()
                val customList = _uiState.value.customList
                if (customList != null) {
                    val day = dayClock.day.value
                    val params = CustomListFilterBuilder.buildQueryParams(customList.filter, day.date, day.zone)
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

    fun saveCustomList(customList: CustomList) {
        viewModelScope.launch {
            customListRepository.upsert(customList)
        }
    }

    fun deleteCustomList() {
        viewModelScope.launch {
            try {
                customListRepository.delete(listId)
                _uiState.update { it.copy(isDeleted = true) }
            } catch (e: Exception) {
                Log.e("CustomListViewModel", "deleteCustomList() failed: ${e.message}", e)
                _uiState.update { it.copy(error = e.message ?: "Failed to delete list") }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
