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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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

@OptIn(ExperimentalCoroutinesApi::class)
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
        // Render from Room with client-side filter + sort. flatMapLatest cancels the previous
        // collector when the list config changes (the old code leaked one collector per edit).
        viewModelScope.launch {
            customListStore.getById(listId)
                .flatMapLatest { customList ->
                    if (customList == null) {
                        flowOf<Pair<CustomList?, List<Task>>>(null to emptyList())
                    } else {
                        val source = if (customList.filter.includeDone) {
                            taskRepository.getAllTasks()
                        } else {
                            taskRepository.getAllOpenTasks()
                        }
                        source.map { tasks ->
                            val filtered = CustomListFilterBuilder.applyClientSideFilters(tasks, customList.filter)
                            customList to CustomListFilterBuilder.sortTasks(
                                filtered,
                                customList.filter.sortBy,
                                customList.filter.orderBy,
                            )
                        }
                    }
                }
                .collect { (customList, tasks) ->
                    _uiState.update { it.copy(customList = customList, tasks = tasks, isLoading = false) }
                }
        }
        // Background network refresh, once per distinct filter config (Room paints first).
        viewModelScope.launch {
            customListStore.getById(listId)
                .map { it?.filter }
                .distinctUntilChanged()
                .collect { filter ->
                    if (filter != null) {
                        taskRepository.refreshAll(CustomListFilterBuilder.buildQueryParams(filter))
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
            val completedIds = _uiState.value.completedTaskIds
            _uiState.update { it.copy(isRefreshing = showSpinner, error = null, completedTaskIds = emptySet()) }
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

    fun saveCustomList(customList: CustomList) {
        viewModelScope.launch {
            customListStore.save(customList)
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
