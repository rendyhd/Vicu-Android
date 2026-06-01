package com.rendyhd.vicu.ui.screens.taskdetail

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.domain.model.Attachment
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskReminder
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.domain.repository.AttachmentRepository
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.util.Constants
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.DescriptionHtml
import com.rendyhd.vicu.util.ImageTokens
import com.rendyhd.vicu.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import javax.inject.Inject

data class TaskDetailUiState(
    val task: Task? = null,
    val originalTask: Task? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null,
    val allProjects: List<Project> = emptyList(),
    val allLabels: List<Label> = emptyList(),
    val subtasks: List<Task> = emptyList(),
    val relations: Map<String, List<Task>> = emptyMap(),
    val attachments: List<Attachment> = emptyList(),
    val showDeleteConfirmation: Boolean = false,
    val isDeleted: Boolean = false,
    val inboxProjectId: Long = 0L,
    val isUploadingImage: Boolean = false,
)

@HiltViewModel
class TaskDetailViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val labelRepository: LabelRepository,
    private val attachmentRepository: AttachmentRepository,
    private val projectRepository: ProjectRepository,
    private val authManager: AuthManager,
    private val behaviorPrefsStore: BehaviorPrefsStore,
) : ViewModel() {

    companion object {
        private const val TAG = "TaskDetailVM"
    }

    private val _uiState = MutableStateFlow(TaskDetailUiState())
    val uiState: StateFlow<TaskDetailUiState> = _uiState.asStateFlow()

    private var taskIdLoaded = 0L

    /** Preserved link HTML stripped from description for display, re-appended on save. */
    private var preservedLinkHtml = ""

    private val _relationSearchQuery = MutableStateFlow("")

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val relationSearchResults: StateFlow<List<Task>> = _relationSearchQuery
        .debounce(250)
        .flatMapLatest { q ->
            if (q.isBlank()) flowOf(emptyList())
            else taskRepository.searchByTitleIncludingDone(q)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun loadTask(taskId: Long) {
        if (taskId == taskIdLoaded) return
        taskIdLoaded = taskId

        // Reset state for the new task so stale data from the previous task doesn't persist
        preservedLinkHtml = ""
        _uiState.update {
            it.copy(
                task = null,
                originalTask = null,
                isLoading = true,
                isDeleted = false,
                error = null,
            )
        }

        viewModelScope.launch {
            taskRepository.getById(taskId).collect { task ->
                if (task != null) {
                    val subtasks = task.relatedTasks["subtask"] ?: emptyList()
                    val relations = task.relatedTasks
                        .filterKeys { it in com.rendyhd.vicu.util.RelationKind.DISPLAYABLE }
                        .filterValues { it.isNotEmpty() }
                    val isFirstLoad = _uiState.value.originalTask == null
                    if (isFirstLoad) {
                        val split = DescriptionHtml.splitForEditor(task.description)
                        preservedLinkHtml = split.linkHtml
                        val displayDesc = ImageTokens.buildValue(split.htmlBody, split.imageRefs)
                        val displayTask = task.copy(description = displayDesc)
                        _uiState.update {
                            it.copy(
                                task = displayTask,
                                originalTask = displayTask,
                                subtasks = subtasks,
                                relations = relations,
                                isLoading = false,
                            )
                        }
                    } else {
                        // Preserve the user's in-progress title/description edits, but adopt
                        // server-confirmed labels from later Room emissions so an added/removed
                        // label is reflected here (issue #6 — the checkbox previously went stale).
                        _uiState.update { st ->
                            st.copy(
                                task = st.task?.copy(labels = task.labels),
                                subtasks = subtasks,
                                relations = relations,
                                isLoading = false,
                            )
                        }
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }

        viewModelScope.launch {
            projectRepository.getAll().collect { projects ->
                _uiState.update { it.copy(allProjects = projects) }
            }
        }

        viewModelScope.launch {
            val inboxId = authManager.getInboxProjectId() ?: 0L
            _uiState.update { it.copy(inboxProjectId = inboxId) }
        }

        viewModelScope.launch {
            labelRepository.getAll().collect { labels ->
                _uiState.update { it.copy(allLabels = labels) }
            }
        }

        viewModelScope.launch {
            attachmentRepository.getByTaskId(taskId).collect { attachments ->
                _uiState.update { it.copy(attachments = attachments) }
            }
        }

        viewModelScope.launch {
            attachmentRepository.refreshForTask(taskId)
        }
    }

    fun updateTitle(title: String) {
        _uiState.update { it.copy(task = it.task?.copy(title = title)) }
    }

    fun updateDescription(description: String) {
        _uiState.update { it.copy(task = it.task?.copy(description = description)) }
    }

    fun setDueDate(dueDate: String) {
        _uiState.update { it.copy(task = it.task?.copy(dueDate = dueDate)) }
    }

    fun clearDueDate() {
        _uiState.update { it.copy(task = it.task?.copy(dueDate = Constants.NULL_DATE_STRING)) }
    }

    fun cyclePriority() {
        _uiState.update {
            val current = it.task?.priority ?: 0
            it.copy(task = it.task?.copy(priority = (current + 1) % 5))
        }
    }

    fun setPriority(value: Int) {
        _uiState.update { it.copy(task = it.task?.copy(priority = value.coerceIn(0, 4))) }
    }

    fun setProject(projectId: Long) {
        _uiState.update { it.copy(task = it.task?.copy(projectId = projectId)) }
    }

    fun addLabel(labelId: Long) {
        val current = _uiState.value.task ?: return
        if (current.labels.any { it.id == labelId }) return
        // Optimistically reflect the label so the picker checkbox + chips update immediately,
        // without waiting for the server round-trip and Room re-emission (issue #6).
        val label = _uiState.value.allLabels.find { it.id == labelId }
        if (label != null) {
            _uiState.update { st ->
                val t = st.task ?: return@update st
                st.copy(task = t.copy(labels = t.labels + label))
            }
        }
        viewModelScope.launch {
            when (val result = labelRepository.addToTask(current.id, labelId)) {
                is NetworkResult.Error -> _uiState.update { st ->
                    // Roll back the optimistic add and surface the failure.
                    val t = st.task ?: return@update st.copy(error = result.message)
                    st.copy(
                        task = t.copy(labels = t.labels.filterNot { it.id == labelId }),
                        error = result.message,
                    )
                }
                else -> {}
            }
        }
    }

    fun removeLabel(labelId: Long) {
        val current = _uiState.value.task ?: return
        val removed = current.labels.find { it.id == labelId } ?: return
        // Optimistic remove.
        _uiState.update { st ->
            val t = st.task ?: return@update st
            st.copy(task = t.copy(labels = t.labels.filterNot { it.id == labelId }))
        }
        viewModelScope.launch {
            when (val result = labelRepository.removeFromTask(current.id, labelId)) {
                is NetworkResult.Error -> _uiState.update { st ->
                    // Roll back the optimistic remove and surface the failure.
                    val t = st.task ?: return@update st.copy(error = result.message)
                    val restored = if (t.labels.none { it.id == labelId }) t.labels + removed else t.labels
                    st.copy(task = t.copy(labels = restored), error = result.message)
                }
                else -> {}
            }
        }
    }

    fun createAndAddLabel(name: String, hexColor: String) {
        val task = _uiState.value.task ?: return
        viewModelScope.launch {
            val label = Label(id = 0, title = name, hexColor = hexColor)
            when (val result = labelRepository.create(label)) {
                is NetworkResult.Success -> {
                    labelRepository.addToTask(task.id, result.data.id)
                }
                is NetworkResult.Error -> _uiState.update { it.copy(error = result.message) }
                else -> {}
            }
        }
    }

    fun addReminder(reminder: TaskReminder) {
        _uiState.update {
            it.copy(task = it.task?.copy(reminders = it.task.reminders + reminder))
        }
    }

    fun removeReminder(index: Int) {
        _uiState.update {
            val updated = it.task?.reminders?.toMutableList()?.apply { removeAt(index) } ?: emptyList()
            it.copy(task = it.task?.copy(reminders = updated))
        }
    }

    fun editReminder(index: Int, reminder: TaskReminder) {
        _uiState.update {
            val updated = it.task?.reminders?.toMutableList()?.apply { set(index, reminder) } ?: emptyList()
            it.copy(task = it.task?.copy(reminders = updated))
        }
    }

    fun createSubtask(title: String) {
        val task = _uiState.value.task ?: return
        if (title.isBlank()) return

        viewModelScope.launch {
            val subtask = Task(
                id = 0,
                title = title,
                projectId = task.projectId,
            )
            when (val result = taskRepository.createSubtask(task.id, subtask)) {
                is NetworkResult.Error -> _uiState.update { it.copy(error = result.message) }
                else -> {}
            }
        }
    }

    fun addRelation(otherTaskId: Long, relationKind: String) {
        val task = _uiState.value.task ?: return
        viewModelScope.launch {
            when (val result = taskRepository.createRelation(task.id, otherTaskId, relationKind)) {
                is NetworkResult.Error -> _uiState.update { it.copy(error = result.message) }
                else -> {}
            }
        }
    }

    fun removeRelation(relationKind: String, otherTaskId: Long) {
        val task = _uiState.value.task ?: return
        viewModelScope.launch {
            when (val result = taskRepository.deleteRelation(task.id, relationKind, otherTaskId)) {
                is NetworkResult.Error -> _uiState.update { it.copy(error = result.message) }
                else -> {}
            }
        }
    }

    fun setRelationSearchQuery(q: String) {
        _relationSearchQuery.value = q
        if (q.isNotBlank()) {
            viewModelScope.launch { taskRepository.refreshAll(mapOf("s" to q)) }
        }
    }

    fun toggleSubtaskDone(subtask: Task) {
        viewModelScope.launch {
            taskRepository.toggleDone(subtask)
        }
    }

    fun uploadAttachment(filePart: MultipartBody.Part) {
        val task = _uiState.value.task ?: return
        viewModelScope.launch {
            when (val result = attachmentRepository.upload(task.id, filePart)) {
                is NetworkResult.Error -> _uiState.update { it.copy(error = result.message) }
                else -> {}
            }
        }
    }

    fun addImageAttachment(filePart: MultipartBody.Part) {
        val taskIdSnapshot = _uiState.value.task?.id ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isUploadingImage = true) }
            when (val result = attachmentRepository.upload(taskIdSnapshot, filePart)) {
                is NetworkResult.Success -> {
                    // Read the LATEST description inside .update so keystrokes typed
                    // during the upload aren't dropped by a pre-launch snapshot.
                    _uiState.update { current ->
                        val latestDesc = current.task?.description ?: ""
                        val newDesc = ImageTokens.appendImageToken(latestDesc, result.data.id)
                        current.copy(
                            task = current.task?.copy(description = newDesc),
                            isUploadingImage = false,
                        )
                    }
                    // Persist immediately so a token added mid-session survives a
                    // process kill before the sheet's onDispose save fires.
                    saveIfChanged()
                }
                is NetworkResult.Error -> _uiState.update {
                    it.copy(isUploadingImage = false, error = result.message)
                }
                else -> _uiState.update { it.copy(isUploadingImage = false) }
            }
        }
    }

    fun deleteAttachment(attachmentId: Long) {
        val task = _uiState.value.task ?: return
        _uiState.update { it.copy(attachments = it.attachments.filter { a -> a.id != attachmentId }) }
        viewModelScope.launch {
            when (val result = attachmentRepository.delete(task.id, attachmentId)) {
                is NetworkResult.Error -> _uiState.update { it.copy(error = result.message) }
                else -> {}
            }
        }
    }

    suspend fun downloadAttachment(attachmentId: Long): ResponseBody? {
        val task = _uiState.value.task ?: return null
        return when (val result = attachmentRepository.download(task.id, attachmentId)) {
            is NetworkResult.Success -> result.data
            else -> null
        }
    }

    fun showDeleteConfirmation() {
        _uiState.update { it.copy(showDeleteConfirmation = true) }
    }

    /**
     * Entry point for the trash button. Respects the "Confirm before deleting"
     * behavior pref — when off, deletes immediately; when on, raises the dialog.
     */
    fun requestDeleteTask() {
        viewModelScope.launch {
            val prefs = behaviorPrefsStore.getPrefs().first()
            if (prefs.confirmBeforeDelete) {
                _uiState.update { it.copy(showDeleteConfirmation = true) }
            } else {
                deleteTask()
            }
        }
    }

    fun dismissDeleteConfirmation() {
        _uiState.update { it.copy(showDeleteConfirmation = false) }
    }

    fun deleteTask() {
        val task = _uiState.value.task ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(showDeleteConfirmation = false) }
            when (val result = taskRepository.delete(task.id)) {
                is NetworkResult.Success -> _uiState.update { it.copy(isDeleted = true) }
                is NetworkResult.Error -> _uiState.update { it.copy(error = result.message) }
                else -> {}
            }
        }
    }

    fun saveIfChanged() {
        val state = _uiState.value
        val task = state.task ?: return
        val original = state.originalTask ?: return

        if (task == original) return

        Log.d(TAG, "saveIfChanged: task has changes, saving...")
        // Re-append preserved link HTML before saving. task.description already
        // contains the rich-text HTML body + [[image:N]] tokens; link metadata
        // is stored separately and stitched back here.
        val (bodyHtml, imageRefs) = ImageTokens.parseValue(task.description)
        val fullDescription = DescriptionHtml.merge(bodyHtml, imageRefs, preservedLinkHtml)
        val taskToSave = task.copy(description = fullDescription)

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            // Send COMPLETE task object (Go zero-value problem)
            when (val result = taskRepository.update(taskToSave)) {
                is NetworkResult.Success -> {
                    val split = DescriptionHtml.splitForEditor(result.data.description)
                    preservedLinkHtml = split.linkHtml
                    val displayDesc = ImageTokens.buildValue(split.htmlBody, split.imageRefs)
                    val displayed = result.data.copy(description = displayDesc)
                    _uiState.update {
                        it.copy(isSaving = false, task = displayed, originalTask = displayed)
                    }
                }
                is NetworkResult.Error -> {
                    _uiState.update { it.copy(isSaving = false, error = result.message) }
                }
                else -> {}
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
