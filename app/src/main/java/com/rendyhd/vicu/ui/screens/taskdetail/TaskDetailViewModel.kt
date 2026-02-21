package com.rendyhd.vicu.ui.screens.taskdetail

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.domain.model.Attachment
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskReminder
import com.rendyhd.vicu.domain.repository.AttachmentRepository
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.util.Constants
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val attachments: List<Attachment> = emptyList(),
    val showDeleteConfirmation: Boolean = false,
    val isDeleted: Boolean = false,
)

@HiltViewModel
class TaskDetailViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val labelRepository: LabelRepository,
    private val attachmentRepository: AttachmentRepository,
    private val projectRepository: ProjectRepository,
) : ViewModel() {

    companion object {
        private const val TAG = "TaskDetailVM"
    }

    private val _uiState = MutableStateFlow(TaskDetailUiState())
    val uiState: StateFlow<TaskDetailUiState> = _uiState.asStateFlow()

    private var taskIdLoaded = 0L

    fun loadTask(taskId: Long) {
        if (taskId == taskIdLoaded) return
        taskIdLoaded = taskId

        viewModelScope.launch {
            taskRepository.getById(taskId).collect { task ->
                if (task != null) {
                    val subtasks = task.relatedTasks["subtask"] ?: emptyList()
                    val isFirstLoad = _uiState.value.originalTask == null
                    _uiState.update {
                        it.copy(
                            task = if (isFirstLoad) task else it.task,
                            originalTask = if (isFirstLoad) task else it.originalTask,
                            subtasks = subtasks,
                            isLoading = false,
                        )
                    }
                    if (isFirstLoad) {
                        _uiState.update { it.copy(task = task, originalTask = task) }
                    }
                }
            }
        }

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

    fun setProject(projectId: Long) {
        _uiState.update { it.copy(task = it.task?.copy(projectId = projectId)) }
    }

    fun addLabel(labelId: Long) {
        val task = _uiState.value.task ?: return
        viewModelScope.launch {
            when (val result = labelRepository.addToTask(task.id, labelId)) {
                is NetworkResult.Error -> _uiState.update { it.copy(error = result.message) }
                else -> {}
            }
        }
    }

    fun removeLabel(labelId: Long) {
        val task = _uiState.value.task ?: return
        viewModelScope.launch {
            when (val result = labelRepository.removeFromTask(task.id, labelId)) {
                is NetworkResult.Error -> _uiState.update { it.copy(error = result.message) }
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

    fun deleteAttachment(attachmentId: Long) {
        val task = _uiState.value.task ?: return
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
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            // Send COMPLETE task object (Go zero-value problem)
            when (val result = taskRepository.update(task)) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(isSaving = false, originalTask = result.data)
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
