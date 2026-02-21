package com.rendyhd.vicu.ui.screens.project

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProjectSection(
    val project: Project,
    val tasks: List<Task>,
    val isExpanded: Boolean = true,
)

data class ProjectUiState(
    val project: Project? = null,
    val sections: List<ProjectSection> = emptyList(),
    val unsectionedTasks: List<Task> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val completedTaskIds: Set<Long> = emptySet(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProjectViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val labelRepository: LabelRepository,
) : ViewModel() {

    private val projectId: Long = savedStateHandle["projectId"]!!

    private val _uiState = MutableStateFlow(ProjectUiState())
    val uiState: StateFlow<ProjectUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // Combine project info, children (sections), parent tasks, and child tasks
            combine(
                projectRepository.getById(projectId),
                projectRepository.getChildren(projectId),
                taskRepository.getByProjectId(projectId),
            ) { project, children, parentTasks ->
                Triple(project, children, parentTasks)
            }.flatMapLatest { (project, children, parentTasks) ->
                if (children.isEmpty()) {
                    flowOf(
                        ProjectUiState(
                            project = project,
                            sections = emptyList(),
                            unsectionedTasks = parentTasks.filter { !it.done },
                            isLoading = false,
                        )
                    )
                } else {
                    // Combine task flows for all child projects
                    val childTaskFlows = children.map { child ->
                        taskRepository.getByProjectId(child.id).map { tasks ->
                            ProjectSection(
                                project = child,
                                tasks = tasks.filter { !it.done },
                            )
                        }
                    }
                    combine(childTaskFlows) { sectionArray ->
                        ProjectUiState(
                            project = project,
                            sections = sectionArray.toList(),
                            unsectionedTasks = parentTasks.filter { !it.done },
                            isLoading = false,
                        )
                    }
                }
            }.collect { newState ->
                _uiState.update { current ->
                    // Preserve expansion state and completedTaskIds
                    val sections = newState.sections.map { section ->
                        val existingExpanded = current.sections
                            .find { it.project.id == section.project.id }
                            ?.isExpanded ?: true
                        section.copy(isExpanded = existingExpanded)
                    }
                    newState.copy(
                        sections = sections,
                        completedTaskIds = current.completedTaskIds,
                    )
                }
            }
        }
        refresh()
    }

    fun toggleSection(sectionIndex: Int) {
        _uiState.update { state ->
            val sections = state.sections.toMutableList()
            if (sectionIndex in sections.indices) {
                sections[sectionIndex] = sections[sectionIndex].copy(
                    isExpanded = !sections[sectionIndex].isExpanded
                )
            }
            state.copy(sections = sections)
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val completedIds = _uiState.value.completedTaskIds
            _uiState.update { it.copy(isRefreshing = true, error = null, completedTaskIds = emptySet()) }
            if (completedIds.isNotEmpty()) taskRepository.deleteLocalByIds(completedIds)
            taskRepository.refreshAll()
            projectRepository.refreshAll()
            labelRepository.refreshAll()
            _uiState.update { it.copy(isRefreshing = false) }
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
            taskRepository.toggleDone(task)
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
