package com.rendyhd.vicu.ui.screens.anytime

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.domain.model.Project
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

class AnytimeViewModel(
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val labelRepository: LabelRepository,
    private val authManager: AuthManager,
    private val refresher: ScreenRefresher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AnytimeUiState())
    val uiState: StateFlow<AnytimeUiState> = _uiState.asStateFlow()

    /** Rows completed on this screen, kept in place for a moment (see [CompletionHold]). */
    val completions = CompletionHold(viewModelScope)

    init {
        viewModelScope.launch {
            completions.heldIds.collect { ids -> _uiState.update { it.copy(completedTaskIds = ids) } }
        }
        viewModelScope.launch {
            authManager.inboxProjectId.collectLatest { inboxId ->
                if (inboxId == null) {
                    _uiState.update { it.copy(isLoading = false) }
                    return@collectLatest
                }
                combine(
                    taskRepository.getAnytimeTasks(inboxId),
                    projectRepository.getAll(),
                    completions.state,
                ) { storedTasks, projects, _ ->
                    val tasks = completions.merge(storedTasks)
                    val projectMap = projects.associateBy { it.id }
                    val tasksByProject = tasks.groupBy { it.projectId }
                    val activeIds = projectMap.keys

                    // Find top-level projects (parentProjectId == 0)
                    val topLevelProjects = projects.filter {
                        (it.parentProjectId == 0L || it.parentProjectId !in activeIds) && it.id != inboxId
                    }
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
        }
        if (refresher.isStale()) refresh()
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
