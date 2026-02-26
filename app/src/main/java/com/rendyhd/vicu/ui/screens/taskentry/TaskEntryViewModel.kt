package com.rendyhd.vicu.ui.screens.taskentry

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.SharedContent
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskReminder
import com.rendyhd.vicu.domain.model.User
import com.rendyhd.vicu.domain.repository.AttachmentRepository
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.util.Constants
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.FileUtils
import com.rendyhd.vicu.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TaskEntryUiState(
    val title: String = "",
    val description: String = "",
    val dueDate: String = "",
    val priority: Int = 0,
    val projectId: Long = 0,
    val selectedLabelIds: Set<Long> = emptySet(),
    val reminders: List<TaskReminder> = emptyList(),
    val isSaving: Boolean = false,
    val savedTaskId: Long? = null,
    val error: String? = null,
    val allProjects: List<Project> = emptyList(),
    val allLabels: List<Label> = emptyList(),
    val pendingAttachmentUris: List<Uri> = emptyList(),
    val pendingAttachmentMimeType: String? = null,
)

@HiltViewModel
class TaskEntryViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val labelRepository: LabelRepository,
    private val attachmentRepository: AttachmentRepository,
    private val authManager: AuthManager,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TaskEntryUiState())
    val uiState: StateFlow<TaskEntryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            projectRepository.getAll().collect { projects ->
                _uiState.update { it.copy(allProjects = projects) }
            }
        }
        viewModelScope.launch {
            labelRepository.getAll().collect { labels ->
                _uiState.update { it.copy(allLabels = labels) }
            }
        }
    }

    fun initWithDefaults(defaultProjectId: Long?) {
        viewModelScope.launch {
            val projectId = defaultProjectId ?: authManager.getInboxProjectId() ?: 0L
            _uiState.update { it.copy(projectId = projectId) }
        }
    }

    fun initWithSharedContent(defaultProjectId: Long?, sharedContent: SharedContent) {
        viewModelScope.launch {
            val projectId = defaultProjectId ?: authManager.getInboxProjectId() ?: 0L
            _uiState.update {
                it.copy(
                    projectId = projectId,
                    title = sharedContent.suggestedTitle ?: "",
                    description = sharedContent.suggestedDescription ?: "",
                    pendingAttachmentUris = sharedContent.fileUris,
                    pendingAttachmentMimeType = sharedContent.mimeType,
                )
            }
        }
    }

    fun removePendingAttachment(index: Int) {
        _uiState.update {
            it.copy(
                pendingAttachmentUris = it.pendingAttachmentUris.toMutableList().apply {
                    removeAt(index)
                },
            )
        }
    }

    fun setTitle(title: String) {
        _uiState.update { it.copy(title = title) }
    }

    fun setDescription(description: String) {
        _uiState.update { it.copy(description = description) }
    }

    fun setDueDate(dueDate: String) {
        _uiState.update { it.copy(dueDate = dueDate) }
    }

    fun clearDueDate() {
        _uiState.update { it.copy(dueDate = Constants.NULL_DATE_STRING) }
    }

    fun setPriority(priority: Int) {
        _uiState.update { it.copy(priority = priority) }
    }

    fun cyclePriority() {
        _uiState.update { it.copy(priority = (it.priority + 1) % 5) }
    }

    fun setProjectId(projectId: Long) {
        _uiState.update { it.copy(projectId = projectId) }
    }

    fun toggleLabel(labelId: Long) {
        _uiState.update {
            val newSet = if (labelId in it.selectedLabelIds) {
                it.selectedLabelIds - labelId
            } else {
                it.selectedLabelIds + labelId
            }
            it.copy(selectedLabelIds = newSet)
        }
    }

    fun addReminder(reminder: TaskReminder) {
        _uiState.update { it.copy(reminders = it.reminders + reminder) }
    }

    fun removeReminder(index: Int) {
        _uiState.update {
            it.copy(reminders = it.reminders.toMutableList().apply { removeAt(index) })
        }
    }

    fun editReminder(index: Int, reminder: TaskReminder) {
        _uiState.update {
            it.copy(reminders = it.reminders.toMutableList().apply { set(index, reminder) })
        }
    }

    fun save() {
        val state = _uiState.value
        var title = state.title.trim()
        if (title.isBlank()) return

        // "!" prefix: strip and set due date to today
        var dueDate = state.dueDate
        if (title.startsWith("!")) {
            title = title.removePrefix("!").trim()
            if (dueDate.isBlank() || DateUtils.isNullDate(dueDate)) {
                dueDate = DateUtils.todayEndIso()
            }
        }

        _uiState.update { it.copy(isSaving = true, error = null) }

        val pendingUris = state.pendingAttachmentUris

        viewModelScope.launch {
            val task = Task(
                id = 0,
                title = title,
                description = state.description,
                dueDate = dueDate,
                priority = state.priority,
                projectId = state.projectId,
                reminders = state.reminders,
            )

            when (val result = taskRepository.create(task)) {
                is NetworkResult.Success -> {
                    val createdTask = result.data
                    // Add labels to the created task
                    for (labelId in state.selectedLabelIds) {
                        labelRepository.addToTask(createdTask.id, labelId)
                    }
                    // Refresh to ensure all screen Flows pick up the new task
                    taskRepository.refreshAll()
                    _uiState.update {
                        it.copy(isSaving = false, savedTaskId = createdTask.id)
                    }

                    // Upload pending attachments in background (fire-and-forget)
                    if (pendingUris.isNotEmpty()) {
                        uploadPendingAttachments(createdTask.id, pendingUris)
                    }
                }
                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(isSaving = false, error = result.message)
                    }
                }
                is NetworkResult.Loading -> {}
            }
        }
    }

    private fun uploadPendingAttachments(taskId: Long, uris: List<Uri>) {
        viewModelScope.launch {
            for (uri in uris) {
                val filePart = FileUtils.uriToMultipartPart(context, uri) ?: continue
                attachmentRepository.upload(taskId, filePart)
            }
            attachmentRepository.refreshForTask(taskId)
        }
    }

    fun reset() {
        _uiState.update {
            it.copy(
                title = "",
                description = "",
                dueDate = "",
                priority = 0,
                selectedLabelIds = emptySet(),
                reminders = emptyList(),
                isSaving = false,
                savedTaskId = null,
                error = null,
                pendingAttachmentUris = emptyList(),
                pendingAttachmentMimeType = null,
            )
        }
    }
}
