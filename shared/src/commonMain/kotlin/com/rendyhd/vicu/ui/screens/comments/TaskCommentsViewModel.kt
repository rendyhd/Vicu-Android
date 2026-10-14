package com.rendyhd.vicu.ui.screens.comments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.auth.TokenStorage
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskComment
import com.rendyhd.vicu.domain.repository.CollaborationRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.Collaboration
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.postIfRefused
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TaskCommentsUiState(
    val taskId: Long = 0L,
    val task: Task? = null,
    /** The thread, oldest first, with what waits in the queue. */
    val comments: List<TaskComment> = emptyList(),
    /** True until the newest page has been read (or could not be). */
    val loading: Boolean = true,
    val hasOlder: Boolean = false,
    val loadingOlder: Boolean = false,
    /** Why the thread could not be read; what is cached still shows. */
    val error: String? = null,
    /** "Notify me": null while not known. */
    val subscribed: Boolean? = null,
    /** Whether Notify me is offered: not with an API token, which may not follow tasks (section 9.7). */
    val canFollow: Boolean = false,
)

/** Where the thread of the open task stands beyond what Room holds. */
private data class ThreadStatus(
    val loading: Boolean = true,
    val hasOlder: Boolean = false,
    val loadingOlder: Boolean = false,
    val pagesRead: Int = 0,
    val error: String? = null,
    val subscribed: Boolean? = null,
)

/**
 * The Comments screen of a task (cross-app semantics 9.5): the thread from Room, oldest first, the
 * newest page read from the server when it opens, older pages on scroll, and the unread mark cleared
 * at once. Posting, editing and deleting are optimistic and queued offline by the repository.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskCommentsViewModel(
    private val collaboration: CollaborationRepository,
    private val taskRepository: TaskRepository,
    private val appMessages: AppMessages,
    private val tokenStorage: TokenStorage,
) : ViewModel() {

    private val taskId = MutableStateFlow(0L)
    private val status = MutableStateFlow(ThreadStatus())
    private var openJob: Job? = null
    private val canFollow = tokenStorage.authMethodFlow.map(Collaboration::canFollowTasks)

    val uiState: StateFlow<TaskCommentsUiState> = taskId
        .flatMapLatest { id ->
            if (id == 0L) {
                flowOf(TaskCommentsUiState())
            } else {
                combine(taskRepository.getById(id), collaboration.comments(id), status, canFollow) { task, comments, s, follow ->
                    TaskCommentsUiState(
                        taskId = id,
                        task = task,
                        comments = comments,
                        loading = s.loading,
                        hasOlder = s.hasOlder,
                        loadingOlder = s.loadingOlder,
                        error = s.error,
                        subscribed = s.subscribed,
                        canFollow = follow,
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, TaskCommentsUiState())

    /** Shows the thread of [id]: marks it read, reads the newest page and whether you follow the task. */
    fun open(id: Long) {
        if (taskId.value == id) return
        openJob?.cancel()
        status.value = ThreadStatus()
        taskId.value = id
        if (id == 0L) return
        openJob = viewModelScope.launch {
            launch { collaboration.markRead(id) }
            launch {
                if (!Collaboration.canFollowTasks(tokenStorage.getAuthMethod())) return@launch
                val subscribed = collaboration.isSubscribed(id)
                if (subscribed is NetworkResult.Success) status.update { it.copy(subscribed = subscribed.data) }
            }
            when (val result = collaboration.refreshComments(id)) {
                is NetworkResult.Success -> status.update { it.copy(loading = false, hasOlder = result.data, pagesRead = 1) }
                is NetworkResult.Error -> status.update { it.copy(loading = false, error = result.message.takeUnless { result.offline }) }
                NetworkResult.Loading -> Unit
            }
        }
    }

    /** Reads the next older page when there is one; the thread is scrolled to its top. */
    fun loadOlder() {
        val id = taskId.value
        val current = status.value
        if (id == 0L || !current.hasOlder || current.loadingOlder || current.loading) return
        status.update { it.copy(loadingOlder = true) }
        viewModelScope.launch {
            when (val result = collaboration.loadOlderComments(id, current.pagesRead + 1)) {
                is NetworkResult.Success ->
                    status.update { it.copy(loadingOlder = false, hasOlder = result.data, pagesRead = current.pagesRead + 1) }
                else -> status.update { it.copy(loadingOlder = false) }
            }
        }
    }

    fun post(html: String) {
        val id = taskId.value.takeIf { it != 0L } ?: return
        viewModelScope.launch { appMessages.postIfRefused(collaboration.postComment(id, html), "post the comment") }
    }

    fun edit(commentId: Long, html: String) {
        val id = taskId.value.takeIf { it != 0L } ?: return
        viewModelScope.launch { appMessages.postIfRefused(collaboration.editComment(id, commentId, html), "save the comment") }
    }

    fun delete(commentId: Long) {
        val id = taskId.value.takeIf { it != 0L } ?: return
        viewModelScope.launch { appMessages.postIfRefused(collaboration.deleteComment(id, commentId), "delete the comment") }
    }

    /** "Notify me": switched at once, put back with a message when the server refuses. */
    fun setSubscribed(on: Boolean) {
        val id = taskId.value.takeIf { it != 0L } ?: return
        val before = status.value.subscribed
        status.update { it.copy(subscribed = on) }
        viewModelScope.launch {
            val result = collaboration.setSubscribed(id, on)
            if (result is NetworkResult.Error) {
                status.update { it.copy(subscribed = before) }
                appMessages.post("Could not change notifications for the task: ${result.message}")
            }
        }
    }
}
