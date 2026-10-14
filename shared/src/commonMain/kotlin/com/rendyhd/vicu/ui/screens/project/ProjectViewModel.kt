package com.rendyhd.vicu.ui.screens.project

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.ProjectSectionPrefsStore
import com.rendyhd.vicu.data.local.ReviewPrefs
import com.rendyhd.vicu.data.local.ReviewPrefsStore
import com.rendyhd.vicu.data.local.SubprojectDisplayMode
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.ui.navigation.NavigationTicker
import com.rendyhd.vicu.ui.screens.shared.CompletionHold
import com.rendyhd.vicu.ui.screens.shared.CompletionToast
import com.rendyhd.vicu.ui.screens.shared.NoCompletionToast
import com.rendyhd.vicu.ui.screens.shared.ProjectActionResult
import com.rendyhd.vicu.ui.screens.shared.ProjectActions
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.ReviewMetadata
import com.rendyhd.vicu.util.ReviewState
import com.rendyhd.vicu.util.dropPositionFor
import com.rendyhd.vicu.util.moveTaskInList
import com.rendyhd.vicu.util.neighbourForMove
import com.rendyhd.vicu.util.sortProjectTasks
import com.rendyhd.vicu.data.sync.ScreenRefresher
import com.rendyhd.vicu.data.sync.refreshErrorToShow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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

/** What the project screen's options menu offers, and what its dialogs need. */
data class ProjectMenuState(
    /** The project the menu is about; null while it is missing or archived, when there is no menu. */
    val project: Project? = null,
    /** Every active project: the parent picker of the edit and new-subproject dialogs. */
    val projects: List<Project> = emptyList(),
    val isInbox: Boolean = false,
    /** Review tracking is on and this project is tracked, so "Mark reviewed" is offered. */
    val canMarkReviewed: Boolean = false,
    val confirmBeforeDelete: Boolean = true,
)

/**
 * The menu for [project]: none for a missing or archived one. "Mark reviewed" needs review tracking
 * on and the project tracked: not excluded by its footer, and not the Inbox while the Inbox is left
 * out of review.
 */
internal fun projectMenuState(
    project: Project?,
    allProjects: List<Project>,
    inboxProjectId: Long?,
    reviewPrefs: ReviewPrefs,
    confirmBeforeDelete: Boolean,
): ProjectMenuState {
    if (project == null || project.isArchived) return ProjectMenuState(confirmBeforeDelete = confirmBeforeDelete)
    val isInbox = inboxProjectId != null && project.id == inboxProjectId
    val tracked = reviewPrefs.enabled &&
        !(reviewPrefs.excludeInbox && isInbox) &&
        ReviewMetadata.parse(project.description).state != ReviewState.EXCLUDED
    return ProjectMenuState(
        project = project,
        projects = allProjects.filter { !it.isArchived },
        isInbox = isInbox,
        canMarkReviewed = tracked,
        confirmBeforeDelete = confirmBeforeDelete,
    )
}

/** An entry of the project screen's options menu. */
enum class ProjectMenuEntry(val label: String, val destructive: Boolean = false) {
    EDIT("Edit project"),
    ADD_SUBPROJECT("Add subproject"),
    SET_INBOX("Set as Inbox"),
    MARK_REVIEWED("Mark reviewed"),
    ARCHIVE("Archive"),
    DELETE("Delete", destructive = true),
}

/**
 * The entries the menu shows, in order. The Inbox is not offered as the Inbox again, nor archived
 * or deleted (another project must be the Inbox first).
 */
internal fun ProjectMenuState.entries(): List<ProjectMenuEntry> = buildList {
    if (project == null) return@buildList
    add(ProjectMenuEntry.EDIT)
    add(ProjectMenuEntry.ADD_SUBPROJECT)
    if (!isInbox) add(ProjectMenuEntry.SET_INBOX)
    if (canMarkReviewed) add(ProjectMenuEntry.MARK_REVIEWED)
    if (!isInbox) {
        add(ProjectMenuEntry.ARCHIVE)
        add(ProjectMenuEntry.DELETE)
    }
}

/** Whether the project screen is leaving because its project was archived or deleted from it. */
enum class ProjectExit {
    /** Nothing under way. */
    NONE,

    /** Archiving or deleting: the "archived" or "not found" state that follows is not an error to show. */
    LEAVING,

    /** Done: the screen goes back. */
    LEFT,
}

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectViewModel(
    savedStateHandle: SavedStateHandle,
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val labelRepository: LabelRepository,
    private val refresher: ScreenRefresher,
    private val behaviorPrefsStore: BehaviorPrefsStore,
    private val projectSectionPrefsStore: ProjectSectionPrefsStore,
    private val projectActions: ProjectActions,
    reviewPrefsStore: ReviewPrefsStore,
    private val appMessages: AppMessages,
    navigationTicker: NavigationTicker = NavigationTicker(),
    completionToast: CompletionToast = NoCompletionToast,
) : ViewModel() {

    private val projectId: Long = savedStateHandle["projectId"]!!

    private val _uiState = MutableStateFlow(ProjectUiState())
    val uiState: StateFlow<ProjectUiState> = _uiState.asStateFlow()

    /** Rows completed on this screen, kept in place for a moment (see [CompletionHold]). */
    val completions = CompletionHold(viewModelScope, navigationTicker = navigationTicker, toast = completionToast)

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

    /**
     * The screen reader's "Move up" / "Move down": the task takes the slot of the one [offset]
     * places away within its group and is stored as the drop of a drag to that slot is. Returns
     * false when there is no such slot (an end of the group, a dated task).
     */
    fun moveTaskBy(taskId: Long, offset: Int): Boolean {
        val state = _uiState.value
        val group = if (state.unsectionedTasks.any { it.id == taskId }) {
            state.unsectionedTasks
        } else {
            findTaskGroup(state.sections, taskId)?.tasks ?: return false
        }
        val toId = neighbourForMove(group, taskId, offset) ?: return false
        if (!onTaskMoved(taskId, toId)) return false
        onTaskDropped(taskId)
        return true
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

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    // --- The options menu (the shared ProjectActions do the work) ---

    /** What the options menu offers for this project. */
    val menu: StateFlow<ProjectMenuState> = combine(
        projectRepository.getById(projectId),
        projectRepository.getAll(),
        projectActions.inboxProjectId,
        reviewPrefsStore.getPrefs(),
        behaviorPrefsStore.getPrefs(),
    ) { project, all, inboxId, reviewPrefs, behaviorPrefs ->
        projectMenuState(project, all, inboxId, reviewPrefs, behaviorPrefs.confirmBeforeDelete)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProjectMenuState())

    private val _exit = MutableStateFlow(ProjectExit.NONE)
    val exit: StateFlow<ProjectExit> = _exit.asStateFlow()

    /** The project as it was before "Mark reviewed", while the snackbar offers to undo it. */
    private val _reviewUndo = MutableStateFlow<Project?>(null)
    val reviewUndo: StateFlow<Project?> = _reviewUndo.asStateFlow()

    /** The project as stored now, or null when it is gone or archived (nothing to act on). */
    private suspend fun currentProject(): Project? =
        projectRepository.getById(projectId).first()?.takeUnless { it.isArchived }

    /** Says what an action came to in the app-wide snackbar; true when it was done. */
    private fun report(result: ProjectActionResult): Boolean = when (result) {
        is ProjectActionResult.Done -> {
            result.message?.let { appMessages.post(it) }
            true
        }
        is ProjectActionResult.Failed -> {
            appMessages.post(result.error)
            false
        }
    }

    fun editProject(name: String, hexColor: String, parentProjectId: Long) {
        viewModelScope.launch {
            val project = currentProject() ?: return@launch
            report(projectActions.edit(project, name, hexColor, parentProjectId))
        }
    }

    /** "Add subproject": the dialog started with this project as the parent, which the user may have changed. */
    fun addSubproject(name: String, hexColor: String, parentProjectId: Long) {
        viewModelScope.launch { report(projectActions.create(name, hexColor, parentProjectId)) }
    }

    fun setAsInbox() {
        viewModelScope.launch {
            val project = currentProject() ?: return@launch
            report(projectActions.setInbox(project.id))
        }
    }

    /** The review screen's "Mark reviewed": the snackbar offers Undo until it goes ([reviewUndo]). */
    fun markReviewed() {
        viewModelScope.launch {
            val project = currentProject() ?: return@launch
            _reviewUndo.value = project
            val result = projectActions.markReviewed(project)
            if (result is ProjectActionResult.Failed) {
                // The review was not recorded: there is nothing to undo.
                _reviewUndo.value = null
                appMessages.post(result.error)
            }
        }
    }

    fun undoReview() {
        val previous = _reviewUndo.value ?: return
        _reviewUndo.value = null
        viewModelScope.launch {
            val result = projectActions.undoReview(previous)
            if (result is ProjectActionResult.Failed) appMessages.post(result.error)
        }
    }

    fun dismissReviewUndo() {
        _reviewUndo.value = null
    }

    /** Archives the project (after the confirmation) and leaves the screen once that is done. */
    fun archive() = leaveAfter { project -> projectActions.archive(project) }

    /** Deletes the project (after the confirmation, when one is asked for) and leaves the screen. */
    fun delete() = leaveAfter { project -> projectActions.delete(project.id) }

    private fun leaveAfter(action: suspend (Project) -> ProjectActionResult) {
        viewModelScope.launch {
            val project = currentProject() ?: return@launch
            _exit.value = ProjectExit.LEAVING
            _exit.value = if (report(action(project))) ProjectExit.LEFT else ProjectExit.NONE
        }
    }
}

private data class ProjectInputs(
    val project: Project?,
    val allProjects: List<Project>,
    val parentTasks: List<Task>,
    val subprojectDisplayMode: SubprojectDisplayMode,
    val collapsedSectionIds: Set<Long>,
)
