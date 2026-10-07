package com.rendyhd.vicu.ui.screens.inbox

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

data class InboxUiState(
    val tasks: List<Task> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val completedTaskIds: Set<Long> = emptySet(),
    val inboxProjectId: Long? = null,
)

class InboxViewModel(
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val labelRepository: LabelRepository,
    private val authManager: AuthManager,
    private val refresher: ScreenRefresher,
) : ViewModel() {

    companion object {
        private const val TAG = "InboxViewModel"
        private const val ARCHIVED_MESSAGE =
            "Your Inbox project is archived. Select an active Inbox project in Settings."
        private const val NO_INBOX_MESSAGE =
            "No Inbox project is selected. Choose one in Settings."
    }

    private val _uiState = MutableStateFlow(InboxUiState())
    val uiState: StateFlow<InboxUiState> = _uiState.asStateFlow()
    /** Rows completed on this screen, kept in place for a moment (see [CompletionHold]). */
    val completions = CompletionHold(viewModelScope)

    init {
        viewModelScope.launch {
            completions.heldIds.collect { ids -> _uiState.update { it.copy(completedTaskIds = ids) } }
        }
        Log.d(TAG, "init: InboxViewModel created")
        viewModelScope.launch {
            // The Inbox project can change while this screen is open (picked in Settings, or set
            // by the setup that follows a sign-in), so it is observed, not read once.
            authManager.inboxProjectId.collectLatest { inboxId ->
                Log.d(TAG, "inboxProjectId=$inboxId")
                _uiState.update { it.copy(inboxProjectId = inboxId) }
                if (inboxId == null) {
                    // Signed in but not set up (the app was closed between sign-in and choosing the
                    // Inbox): say so, and carry on when the choice is made.
                    _uiState.update { it.copy(tasks = emptyList(), isLoading = false, error = NO_INBOX_MESSAGE) }
                    return@collectLatest
                }
                combine(
                    taskRepository.getInboxTasks(inboxId),
                    projectRepository.getAll(),
                    completions.state,
                ) { tasks, activeProjects, _ ->
                    completions.merge(tasks) to activeProjects.any { it.id == inboxId }
                }.collect { (tasks, inboxIsActive) ->
                    Log.d(TAG, "Flow emission: ${tasks.size} tasks for inboxId=$inboxId, active=$inboxIsActive")
                    _uiState.update {
                        it.copy(
                            tasks = if (inboxIsActive) tasks else emptyList(),
                            isLoading = false,
                            error = when {
                                !inboxIsActive -> ARCHIVED_MESSAGE
                                // Only these notices go away once the Inbox is usable again; a refresh
                                // error that is waiting to be shown stays.
                                it.error == ARCHIVED_MESSAGE || it.error == NO_INBOX_MESSAGE -> null
                                else -> it.error
                            },
                        )
                    }
                }
            }
        }
        if (refresher.isStale()) refresh()
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
