package com.rendyhd.vicu.ui.screens.anytime

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AnytimeSection(
    val project: Project,
    val tasks: List<Task>,
    val isExpanded: Boolean = true,
)

data class AnytimeProjectGroup(
    val project: Project,
    val unsectionedTasks: List<Task>,
    val sections: List<AnytimeSection>,
    val isExpanded: Boolean = true,
)

data class AnytimeUiState(
    val projectGroups: List<AnytimeProjectGroup> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val completedTaskIds: Set<Long> = emptySet(),
)

@HiltViewModel
class AnytimeViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val labelRepository: LabelRepository,
    private val authManager: AuthManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AnytimeUiState())
    val uiState: StateFlow<AnytimeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val inboxId = authManager.getInboxProjectId() ?: return@launch
            combine(
                taskRepository.getAnytimeTasks(inboxId),
                projectRepository.getAll(),
            ) { tasks, projects ->
                val projectMap = projects.associateBy { it.id }
                val tasksByProject = tasks.groupBy { it.projectId }

                // Find top-level projects (parentProjectId == 0)
                val topLevelProjects = projects.filter { it.parentProjectId == 0L && it.id != inboxId }
                // Build child map: parentId -> list of children
                val childrenByParent = projects.filter { it.parentProjectId != 0L }
                    .groupBy { it.parentProjectId }

                topLevelProjects.mapNotNull { parent ->
                    val parentTasks = tasksByProject[parent.id] ?: emptyList()
                    val children = childrenByParent[parent.id] ?: emptyList()
                    val childSections = children.mapNotNull { child ->
                        val childTasks = tasksByProject[child.id] ?: emptyList()
                        if (childTasks.isEmpty()) return@mapNotNull null
                        AnytimeSection(project = child, tasks = childTasks)
                    }.sortedBy { it.project.title.lowercase() }

                    // Skip this project entirely if it has no tasks and no child sections with tasks
                    if (parentTasks.isEmpty() && childSections.isEmpty()) return@mapNotNull null

                    AnytimeProjectGroup(
                        project = parent,
                        unsectionedTasks = parentTasks,
                        sections = childSections,
                    )
                }.sortedBy { it.project.title.lowercase() }
            }.collect { groups ->
                _uiState.update { current ->
                    // Preserve expansion state across data refreshes
                    val mergedGroups = groups.map { group ->
                        val existing = current.projectGroups.find { it.project.id == group.project.id }
                        val sections = group.sections.map { section ->
                            val existingSection = existing?.sections
                                ?.find { it.project.id == section.project.id }
                            section.copy(isExpanded = existingSection?.isExpanded ?: true)
                        }
                        group.copy(
                            isExpanded = existing?.isExpanded ?: true,
                            sections = sections,
                        )
                    }
                    current.copy(
                        projectGroups = mergedGroups,
                        isLoading = false,
                    )
                }
            }
        }
        refresh()
    }

    fun toggleProject(projectIndex: Int) {
        _uiState.update { state ->
            val groups = state.projectGroups.toMutableList()
            if (projectIndex in groups.indices) {
                groups[projectIndex] = groups[projectIndex].copy(
                    isExpanded = !groups[projectIndex].isExpanded
                )
            }
            state.copy(projectGroups = groups)
        }
    }

    fun toggleSection(projectIndex: Int, sectionIndex: Int) {
        _uiState.update { state ->
            val groups = state.projectGroups.toMutableList()
            if (projectIndex in groups.indices) {
                val group = groups[projectIndex]
                val sections = group.sections.toMutableList()
                if (sectionIndex in sections.indices) {
                    sections[sectionIndex] = sections[sectionIndex].copy(
                        isExpanded = !sections[sectionIndex].isExpanded
                    )
                }
                groups[projectIndex] = group.copy(sections = sections)
            }
            state.copy(projectGroups = groups)
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
