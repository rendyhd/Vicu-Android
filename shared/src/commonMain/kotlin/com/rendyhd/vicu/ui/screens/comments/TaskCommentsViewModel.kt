package com.rendyhd.vicu.ui.screens.comments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.auth.TokenStorage
import com.rendyhd.vicu.domain.model.Attachment
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskComment
import com.rendyhd.vicu.domain.repository.AttachmentRepository
import com.rendyhd.vicu.domain.repository.CollaborationRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.CollabUser
import com.rendyhd.vicu.util.Collaboration
import com.rendyhd.vicu.util.CommentSegment
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.PlatformFiles
import com.rendyhd.vicu.util.postIfRefused
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A file added to the comment being written: on the task once [attachment] is known. */
data class StagedAttachment(
    /** Tells it apart while it uploads, before it has an id. */
    val key: Long,
    val name: String,
    /** Null while the file uploads. */
    val attachment: Attachment? = null,
)

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
    /** The task's attachments as far as known: a comment's files take their name, type and size from here. */
    val attachments: List<Attachment> = emptyList(),
    /** [attachments] is what the server just said, so a file missing from it is gone. */
    val attachmentsRead: Boolean = false,
    /** The attachments being fetched to open, save or share. */
    val downloading: Set<Long> = emptySet(),
    /** The files added to the comment being written on this task. */
    val staged: List<StagedAttachment> = emptyList(),
)

/** Where the thread of the open task stands beyond what Room holds. */
private data class ThreadStatus(
    val loading: Boolean = true,
    val hasOlder: Boolean = false,
    val loadingOlder: Boolean = false,
    val pagesRead: Int = 0,
    val error: String? = null,
    val subscribed: Boolean? = null,
    val attachmentsRead: Boolean = false,
    val downloading: Set<Long> = emptySet(),
)

/**
 * The Comments screen of a task (cross-app semantics 9.5): the thread from Room, oldest first, the
 * newest page read from the server when it opens, older pages on scroll, and the unread mark cleared
 * at once. Posting, editing and deleting are optimistic and queued offline by the repository.
 *
 * A file in a comment is a task attachment the comment points at: it uploads as soon as it is picked
 * and the comment names it when it is sent. What is added stays with its task while the app runs,
 * also when the thread is left for another task.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskCommentsViewModel(
    private val collaboration: CollaborationRepository,
    private val taskRepository: TaskRepository,
    private val attachmentRepository: AttachmentRepository,
    private val platformFiles: PlatformFiles,
    private val appMessages: AppMessages,
    private val tokenStorage: TokenStorage,
) : ViewModel() {

    private val taskId = MutableStateFlow(0L)
    private val status = MutableStateFlow(ThreadStatus())
    private var openJob: Job? = null
    private val canFollow = tokenStorage.authMethodFlow.map(Collaboration::canFollowTasks)

    /** The files added per task, in the order they were picked. */
    private val staged = MutableStateFlow<Map<Long, List<StagedAttachment>>>(emptyMap())
    private val uploads = mutableMapOf<Long, Job>()
    private var nextStagedKey = 1L

    val uiState: StateFlow<TaskCommentsUiState> = taskId
        .flatMapLatest { id ->
            if (id == 0L) {
                flowOf(TaskCommentsUiState())
            } else {
                val files = combine(attachmentRepository.getByTaskId(id), staged) { known, added -> known to added[id].orEmpty() }
                combine(taskRepository.getById(id), collaboration.comments(id), status, canFollow, files) { task, comments, s, follow, (known, added) ->
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
                        attachments = known,
                        attachmentsRead = s.attachmentsRead,
                        downloading = s.downloading,
                        staged = added,
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
            // After the comments, so a file the newest comment names is on the list that says what
            // is gone. A task still waiting to sync has none on the server to ask about.
            if (id > 0 && attachmentRepository.refreshForTask(id) is NetworkResult.Success) {
                status.update { it.copy(attachmentsRead = true) }
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

    /**
     * Sends [text] with the files added so far. False when there is nothing to send or a file is
     * still uploading, and then the draft stays as it is.
     */
    fun post(text: String, members: List<CollabUser>): Boolean {
        val id = taskId.value.takeIf { it != 0L } ?: return false
        val added = staged.value[id].orEmpty()
        if (added.any { it.attachment == null }) return false
        if (added.isEmpty() && Collaboration.encodeCommentHtml(text, members).isEmpty()) return false
        staged.update { it - id }
        viewModelScope.launch {
            val server = tokenStorage.getVikunjaUrl().orEmpty()
            val files = added.mapNotNull { it.attachment }.map { file ->
                CommentSegment.Attachment(
                    attachmentId = file.id,
                    name = file.fileName,
                    image = Collaboration.isInlineImageType(file.mimeType),
                    url = Collaboration.commentAttachmentAddress(server, id, file.id),
                )
            }
            val html = Collaboration.encodeCommentHtml(text, members, files)
            appMessages.postIfRefused(collaboration.postComment(id, html), "post the comment")
        }
        return true
    }

    /** Adds the picked file to the comment being written: it goes to the task at once. */
    fun attach(uriString: String) {
        val id = taskId.value.takeIf { it != 0L } ?: return
        if (id < 0) {
            appMessages.post("This task has not reached the server yet. You can attach files once it has.")
            return
        }
        val key = nextStagedKey++
        val name = platformFiles.getDisplayName(uriString)?.takeIf { it.isNotBlank() } ?: "File"
        staged.update { it + (id to it[id].orEmpty() + StagedAttachment(key, name)) }
        val job = viewModelScope.launch {
            val result = attachmentRepository.uploadPicked(id, uriString)
            uploads.remove(key)
            when (result) {
                is NetworkResult.Success -> updateStaged(id) { list ->
                    list.map { if (it.key == key) it.copy(name = result.data.fileName.ifBlank { it.name }, attachment = result.data) else it }
                }
                is NetworkResult.Error -> {
                    updateStaged(id) { list -> list.filterNot { it.key == key } }
                    appMessages.post("Could not attach \"$name\": ${result.message}")
                }
                NetworkResult.Loading -> Unit
            }
        }
        uploads[key] = job
    }

    /** Takes a file out of the comment being written, and off the task: nobody has seen it yet. */
    fun removeStaged(key: Long) {
        val id = taskId.value.takeIf { it != 0L } ?: return
        val item = staged.value[id].orEmpty().firstOrNull { it.key == key } ?: return
        updateStaged(id) { list -> list.filterNot { it.key == key } }
        uploads.remove(key)?.cancel()
        val file = item.attachment ?: return
        viewModelScope.launch {
            val result = attachmentRepository.delete(id, file.id)
            if (result is NetworkResult.Error) appMessages.post("Could not remove \"${item.name}\": ${result.message}")
        }
    }

    /**
     * Puts back the files of a draft after the app was ended, by the ids the screen saved. The files
     * stayed on the task; only the link to the draft is lost with the process.
     */
    fun restoreStaged(id: Long, attachmentIds: List<Long>) {
        if (attachmentIds.isEmpty() || staged.value[id].orEmpty().isNotEmpty()) return
        viewModelScope.launch {
            val known = attachmentRepository.getByTaskId(id).first().associateBy { it.id }
            val back = attachmentIds.mapNotNull { known[it] }.map { StagedAttachment(nextStagedKey++, it.fileName, it) }
            updateStaged(id) { current -> if (current.isEmpty()) back else current }
        }
    }

    private fun updateStaged(id: Long, change: (List<StagedAttachment>) -> List<StagedAttachment>) {
        staged.update { all ->
            val next = change(all[id].orEmpty())
            if (next.isEmpty()) all - id else all + (id to next)
        }
    }

    /** The attachment a comment points at, with what the task's list knows about it. */
    private fun attachmentFor(segment: CommentSegment.Attachment): Attachment {
        val id = taskId.value
        return uiState.value.attachments.firstOrNull { it.id == segment.attachmentId }
            ?: Attachment(id = segment.attachmentId, taskId = id, fileName = segment.name.ifBlank { "attachment" })
    }

    /** Fetches the file to the app cache and opens it in another app. */
    fun openAttachment(segment: CommentSegment.Attachment) {
        val file = attachmentFor(segment)
        withCached(file, report = null) { path -> platformFiles.openFile(path, file.mimeType) }
    }

    /** Fetches the file to the app cache and offers it to other apps. */
    fun shareAttachment(segment: CommentSegment.Attachment, report: ((String) -> Unit)? = null) {
        val file = attachmentFor(segment)
        withCached(file, report) { path -> platformFiles.shareFile(path, file.mimeType) }
    }

    /** Fetches the file and saves a copy where the user keeps files (Downloads; Files on iOS). */
    fun saveAttachment(segment: CommentSegment.Attachment, report: ((String) -> Unit)? = null) {
        val file = attachmentFor(segment)
        withCached(file, report) { path -> platformFiles.saveFile(path, file.fileName, file.mimeType) }
    }

    /** What there is to say goes to [report] when given (the image viewer covers the snackbar). */
    private fun withCached(file: Attachment, report: ((String) -> Unit)?, present: suspend (String) -> String?) {
        if (file.id in status.value.downloading) return
        status.update { it.copy(downloading = it.downloading + file.id) }
        viewModelScope.launch {
            val result = attachmentRepository.downloadToCache(file)
            status.update { it.copy(downloading = it.downloading - file.id) }
            val message = when (result) {
                is NetworkResult.Success -> present(result.data)
                is NetworkResult.Error -> "Could not download \"${file.fileName}\": ${result.message}"
                NetworkResult.Loading -> null
            } ?: return@launch
            if (report != null) report(message) else appMessages.post(message)
        }
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
