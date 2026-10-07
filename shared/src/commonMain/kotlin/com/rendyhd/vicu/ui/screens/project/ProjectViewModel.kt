package com.rendyhd.vicu.ui.screens.project

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.ProjectSectionPrefsStore
import com.rendyhd.vicu.data.local.SubprojectDisplayMode
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.ui.screens.shared.CompletionHold
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.dropPositionFor
import com.rendyhd.vicu.util.moveTaskInList
import com.rendyhd.vicu.util.sortProjectTasks
import com.rendyhd.vicu.data.sync.ScreenRefresher
import com.rendyhd.vicu.data.sync.refreshErrorToShow
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

data class ProjectUiState(
    val project: Project? = null,
    val childProjects: List<Project> = emptyList(),
    val sections: List<ProjectSection> = emptyList(),
    val unsectionedTasks: List<Task> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val completedTaskIds: Set<Long> = emptySet(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectViewModel(
    savedStateHandle: SavedStateHandle,
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val labelRepository: LabelRepository,
    private val refresher: ScreenRefresher,
    private val behaviorPrefsStore: BehaviorPrefsStore,
    private val projectSectionPrefsStore: ProjectSectionPrefsStore,
) : ViewModel() {

    private val projectId: Long = savedStateHandle["projectId"]!!

    private val _uiState = MutableStateFlow(ProjectUiState())
    val uiState: StateFlow<ProjectUiState> = _uiState.asStateFlow()

    /** Rows completed on this screen, kept in place for a moment (see [CompletionHold]). */
    val completions = CompletionHold(viewModelScope)

    /** The "not found" or "archived" message of the last project state, told apart from action errors. */
    private var projectError: String? = null

    init {
        viewModelScope.launch {
            completions.heldIds.collect { ids -> _uiState.update { it.copy(completedTaskIds = ids) } }
        }
        viewModelScope.launch {
            combine(
                projectRepository.getById(projectId),
                projectRepository.getAll(),
                taskRepository.getByProjectId(projectId),
                behaviorPrefsStore.getPrefs(),
                projectSectionPrefsStore.collapsedSectionIds(projectId),
            ) { project, allProjects, parentTasks, behaviorPrefs, collapsedSectionIds ->
                ProjectInputs(
                    project = project,
                    allProjects = allProjects,
                    parentTasks = parentTasks,
                    subprojectDisplayMode = behaviorPrefs.subprojectDisplayMode,
                    collapsedSectionIds = collapsedSectionIds,
                )
            }.flatMapLatest { inputs ->
                val (project, allProjects, parentTasks, subprojectDisplayMode, collapsedSectionIds) = inputs
                if (project == null || project.isArchived) {
                    return@flatMapLatest flowOf(
                        ProjectUiState(
                            project = project,
                            isLoading = false,
                            error = if (project?.isArchived == true) {
                                "This project is archived. Restore it from Settings to view its tasks."
                            } else {
                                "Project not found"
                            },
                        ),
                    )
                }
                // Exclude archived projects (and, by pruning the chain, their whole
                // subtrees) so archived sub-projects don't surface as sections — matching
                // how the drawer hides archived projects from navigation.
                val activeProjects = allProjects
                val unsectioned = sortProjectTasks(parentTasks.filter { !it.done })
                if (subprojectDisplayMode == SubprojectDisplayMode.PROJECT_ROWS) {
                    flowOf(
                        ProjectUiState(
                            project = project,
                            childProjects = directChildProjects(projectId, activeProjects),
                            sections = emptyList(),
                            unsectionedTasks = unsectioned,
                            isLoading = false,
                        )
                    )
                } else {
                    val descendants = collectDescendants(projectId, activeProjects)
                    if (descendants.isEmpty()) {
                        flowOf(
                            ProjectUiState(
                                project = project,
                                sections = emptyList(),
                                unsectionedTasks = unsectioned,
                                isLoading = false,
                            )
                        )
                    } else {
                        // One task flow per descendant; combine rebuilds the tree whenever any changes.
                        val taskFlows = descendants.map { descendant ->
                            taskRepository.getByProjectId(descendant.id).map { tasks ->
                                descendant.id to sortProjectTasks(tasks.filter { !it.done })
                            }
                        }
                        combine(taskFlows) { pairs ->
                            ProjectUiState(
                                project = project,
                                sections = restoreExpansion(
                                    buildSectionTree(projectId, activeProjects, pairs.toMap()),
                                    collapsedSectionIds,
                                ),
                                unsectionedTasks = unsectioned,
                                isLoading = false,
                            )
                        }
                    }
                }
            }.let { upstream ->
                // Merge the held rows back in whenever they change, not only when the stored
                // lists do.
                combine(upstream, completions.state) { state, _ -> withHeldRows(state) }
            }.collect { newState ->
                val previousProjectError = projectError
                projectError = newState.error
                _uiState.update { current ->
                    newState.copy(
                        sections = preserveExpansion(newState.sections, current.sections),
                        completedTaskIds = current.completedTaskIds,
                        // The lists emit whenever a task changes; that must not wipe the spinner
                        // or an error that is still waiting to be shown. The "not found" and
                        // "archived" messages describe the project, not an action: they go when
                        // the project does.
                        isRefreshing = current.isRefreshing,
                        error = newState.error ?: current.error?.takeUnless { it == previousProjectError },
                    )
                }
            }
        }
        if (refresher.isStale()) refresh()
    }

    /** [state] with the held rows back in the lists they were completed from. */
    private fun withHeldRows(state: ProjectUiState): ProjectUiState {
        if (state.project == null || state.project.isArchived) return state
        return state.copy(
            unsectionedTasks = completions.merge(state.unsectionedTasks, listScope = projectId),
            sections = withHeldRows(state.sections),
        )
    }

    private fun withHeldRows(sections: List<ProjectSection>): List<ProjectSection> =
        sections.map { section ->
            section.copy(
                tasks = completions.merge(section.tasks, listScope = section.project.id),
                children = withHeldRows(section.children),
            )
        }

    /**
     * Live reorder while dragging: move [fromId] into the slot of [toId] within its group
     * (unsectioned list or one section). Cross-group and dated-task moves are vetoed by
     * moveTaskInList. Returns true when a move was applied. Uses an explicit CAS loop so
     * the return value is tied to the attempt that actually landed (update {} may retry
     * its lambda, which would leave a side-channel flag stale).
     */
    fun onTaskMoved(fromId: Long, toId: Long): Boolean {
        while (true) {
            val current = _uiState.value
            val next = stateWithMove(current, fromId, toId) ?: return false
            if (_uiState.compareAndSet(current, next)) return true
        }
    }

    /** Returns [state] with the move applied, or null when the move is vetoed. */
    private fun stateWithMove(state: ProjectUiState, fromId: Long, toId: Long): ProjectUiState? {
        moveTaskInList(state.unsectionedTasks, fromId, toId)?.let { reordered ->
            return state.copy(unsectionedTasks = reordered)
        }
        val movedSections = moveTaskInSections(state.sections, fromId, toId) ?: return null
        return state.copy(sections = movedSections)
    }

    /** Drag released: persist the dropped task's new position from its current neighbors. */
    fun onTaskDropped(taskId: Long) {
        val state = _uiState.value
        val inUnsectioned = state.unsectionedTasks.any { it.id == taskId }
        val (tasks, groupProjectId) = if (inUnsectioned) {
            state.unsectionedTasks to projectId
        } else {
            val section = findTaskGroup(state.sections, taskId) ?: return
            section.tasks to section.project.id
        }
        val newPosition = dropPositionFor(tasks, taskId) ?: return
        viewModelScope.launch {
            taskRepository.updatePosition(taskId, groupProjectId, newPosition)
        }
    }

    fun toggleSection(sectionProjectId: Long) {
        val section = findProjectSection(_uiState.value.sections, sectionProjectId) ?: return
        val isExpanded = !section.isExpanded
        _uiState.update { state ->
            state.copy(sections = toggleSectionExpanded(state.sections, sectionProjectId))
        }
        viewModelScope.launch {
            projectSectionPrefsStore.setExpanded(
                rootProjectId = projectId,
                sectionProjectId = sectionProjectId,
                isExpanded = isExpanded,
            )
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

private data class ProjectInputs(
    val project: Project?,
    val allProjects: List<Project>,
    val parentTasks: List<Task>,
    val subprojectDisplayMode: SubprojectDisplayMode,
    val collapsedSectionIds: Set<Long>,
)
