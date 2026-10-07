package com.rendyhd.vicu.ui.screens.today

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.OccurrenceStatus
import com.rendyhd.vicu.domain.model.RoutineDay
import com.rendyhd.vicu.domain.model.RoutineOccurrence
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.RoutineRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.ui.screens.shared.CompletionHold
import com.rendyhd.vicu.ui.screens.shared.TaskProjectGroup
import com.rendyhd.vicu.ui.screens.shared.buildTaskProjectGroups
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.data.sync.ScreenRefresher
import com.rendyhd.vicu.data.sync.refreshErrorToShow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TodayUiState(
    /** Open tasks whose local due date is before today, grouped by project; shown above Today. */
    val overdueGroups: List<TaskProjectGroup> = emptyList(),
    /** Open tasks whose local due date is today, whatever the time of day. */
    val projectGroups: List<TaskProjectGroup> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val completedTaskIds: Set<Long> = emptySet(),
    val routineDay: RoutineDay = RoutineDay("", emptyList()),
)

class TodayViewModel(
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val labelRepository: LabelRepository,
    private val routineRepository: RoutineRepository,
    private val authManager: AuthManager,
    private val refresher: ScreenRefresher,
    private val dayClock: DayClock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TodayUiState())
    val uiState: StateFlow<TodayUiState> = _uiState.asStateFlow()

    /** Rows completed on this screen, kept in place for a moment (see [CompletionHold]). */
    val completions = CompletionHold(viewModelScope)

    private companion object {
        /** [CompletionHold] list scopes: a row keeps its place within its own section. */
        const val TODAY_SCOPE = 0L
        const val OVERDUE_SCOPE = 1L
    }

    /** The routines of the current day; switches to the new day at midnight. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val routinesForToday = dayClock.today.flatMapLatest { date ->
        routineRepository.observeDay(date.toString())
    }

    init {
        viewModelScope.launch {
            completions.heldIds.collect { ids -> _uiState.update { it.copy(completedTaskIds = ids) } }
        }
        viewModelScope.launch {
            // Once at start and again whenever the day changes (yesterday's open health
            // occurrences are closed out).
            dayClock.today.collect { routineRepository.finalizeAndPrune() }
        }
        viewModelScope.launch {
            routinesForToday.collect { day ->
                _uiState.update { it.copy(routineDay = day) }
            }
        }
        viewModelScope.launch {
            authManager.inboxProjectId.collectLatest { inboxId ->
                combine(
                    taskRepository.getTodayTasks(),
                    projectRepository.getAll(),
                    completions.state,
                    // The sections follow the day: a task due today is overdue once it is tomorrow.
                    dayClock.day,
                ) { tasks, projects, _, day ->
                    val overdue = tasks.filter { DueDates.bucket(it.dueDate, day.date, day.zone) == DueDates.Bucket.OVERDUE }
                    val today = tasks.filter { DueDates.bucket(it.dueDate, day.date, day.zone) == DueDates.Bucket.TODAY }
                    buildTaskProjectGroups(completions.merge(overdue, OVERDUE_SCOPE), projects, inboxId) to
                        buildTaskProjectGroups(completions.merge(today, TODAY_SCOPE), projects, inboxId)
                }.collect { (overdueGroups, todayGroups) ->
                    _uiState.update { current ->
                        // Preserve per-project expansion across refreshes, separately per section.
                        current.copy(
                            overdueGroups = overdueGroups.keepExpansion(current.overdueGroups),
                            projectGroups = todayGroups.keepExpansion(current.projectGroups),
                            isLoading = false,
                        )
                    }
                }
            }
        }
        if (refresher.isStale()) refresh()
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

    fun toggleOverdueProject(projectId: Long) {
        _uiState.update { state ->
            state.copy(
                overdueGroups = state.overdueGroups.map {
                    if (it.projectId == projectId) it.copy(isExpanded = !it.isExpanded) else it
                },
            )
        }
    }

    private fun List<TaskProjectGroup>.keepExpansion(previous: List<TaskProjectGroup>): List<TaskProjectGroup> =
        map { g -> g.copy(isExpanded = previous.find { it.projectId == g.projectId }?.isExpanded ?: true) }

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

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun toggleRoutine(occurrence: RoutineOccurrence) {
        viewModelScope.launch {
            val status = if (occurrence.status == OccurrenceStatus.COMPLETED) {
                OccurrenceStatus.PENDING
            } else {
                OccurrenceStatus.COMPLETED
            }
            when (val result = routineRepository.setOccurrenceStatus(
                occurrence.routine.definition.id,
                occurrence.scheduledDate,
                occurrence.slot.id,
                status,
            )) {
                is NetworkResult.Error -> _uiState.update { it.copy(error = result.message) }
                else -> Unit
            }
        }
    }

    fun skipRoutine(occurrence: RoutineOccurrence) {
        viewModelScope.launch {
            when (val result = routineRepository.setOccurrenceStatus(
                occurrence.routine.definition.id,
                occurrence.scheduledDate,
                occurrence.slot.id,
                OccurrenceStatus.SKIPPED,
            )) {
                is NetworkResult.Error -> _uiState.update { it.copy(error = result.message) }
                else -> Unit
            }
        }
    }
}
