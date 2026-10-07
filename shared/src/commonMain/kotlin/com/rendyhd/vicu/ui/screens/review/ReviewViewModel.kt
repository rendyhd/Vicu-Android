package com.rendyhd.vicu.ui.screens.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.local.ReviewPrefs
import com.rendyhd.vicu.data.sync.refreshErrorToShow
import com.rendyhd.vicu.data.local.ReviewPrefsStore
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.ReviewMetadata
import com.rendyhd.vicu.util.ReviewState
import com.rendyhd.vicu.util.ReviewStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

enum class ReviewTab { DUE, ALL }

data class ReviewItem(
    val project: Project,
    val status: ReviewStatus,
)

/** A child project shown nested under an expanded review item, with its open tasks. */
data class ReviewSubProject(
    val project: Project,
    val tasks: List<Task>,
)

/** Lazily-loaded contents of an expanded review item: the project's open tasks + its subprojects. */
data class ReviewProjectContent(
    val tasks: List<Task> = emptyList(),
    val subProjects: List<ReviewSubProject> = emptyList(),
    val isLoading: Boolean = true,
)

data class ReviewUiState(
    val tab: ReviewTab = ReviewTab.DUE,
    val due: List<ReviewItem> = emptyList(),
    val all: List<ReviewItem> = emptyList(),
    val reviewedThisSession: Set<Long> = emptySet(),
    val expanded: Set<Long> = emptySet(),
    val content: Map<Long, ReviewProjectContent> = emptyMap(),
    val enabled: Boolean = true,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val undo: Project? = null, // previous project state for the last mark-reviewed
    val error: String? = null,
)

/** What the review lists are built from. */
private data class ReviewInputs(
    val projects: List<Project>,
    val prefs: ReviewPrefs,
    val inbox: Long?,
    val reviewed: Set<Long>,
    val today: LocalDate,
)

class ReviewViewModel(
    private val projectRepository: ProjectRepository,
    private val taskRepository: TaskRepository,
    private val reviewPrefsStore: ReviewPrefsStore,
    private val authManager: AuthManager,
    private val dayClock: DayClock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReviewUiState())
    val uiState: StateFlow<ReviewUiState> = _uiState.asStateFlow()

    private val inboxId = MutableStateFlow<Long?>(null)

    // Session-only "reviewed" set kept OUT of _uiState so that updating UI state (expand,
    // undo, lazy content) doesn't feed back into the combine and re-run buildState on every tap.
    private val reviewedThisSession = MutableStateFlow<Set<Long>>(emptySet())

    init {
        viewModelScope.launch { authManager.inboxProjectId.collect { inboxId.value = it } }
        viewModelScope.launch {
            combine(
                projectRepository.getAll(),
                reviewPrefsStore.getPrefs(),
                inboxId,
                reviewedThisSession,
                // A review that falls due at midnight shows up without leaving the screen.
                dayClock.today,
            ) { projects, prefs, inbox, reviewed, today ->
                ReviewInputs(projects, prefs, inbox, reviewed, today)
            }.collect { inputs ->
                // Built from the state as it is when this runs, so an action or a refresh that set
                // the error or the undo in the meantime is not overwritten.
                _uiState.update { current ->
                    buildState(inputs.projects, inputs.prefs, inputs.inbox, inputs.reviewed, inputs.today, current)
                }
            }
        }
        refresh(manual = false)
    }

    private fun buildState(
        projects: List<Project>,
        prefs: ReviewPrefs,
        inbox: Long?,
        reviewed: Set<Long>,
        today: LocalDate,
        state: ReviewUiState,
    ): ReviewUiState {
        val tracked = projects
            .asSequence()
            .filterNot { it.isArchived }
            .filterNot { prefs.excludeInbox && inbox != null && it.id == inbox }
            .map {
                ReviewItem(
                    it,
                    ReviewMetadata.computeStatus(ReviewMetadata.parse(it.description), prefs.defaultCadenceDays, today),
                )
            }
            .filter { it.status.metadata.state != ReviewState.EXCLUDED }
            .sortedBy { it.status.daysUntilDue ?: Long.MIN_VALUE }
            .toList()
        val due = tracked.filter { it.status.isOverdue || it.project.id in reviewed }
        return state.copy(
            all = tracked,
            due = due,
            reviewedThisSession = reviewed,
            enabled = prefs.enabled,
            isLoading = false,
        )
    }

    fun setTab(tab: ReviewTab) = _uiState.update { it.copy(tab = tab) }

    fun toggleExpanded(projectId: Long) {
        val expanding = projectId !in _uiState.value.expanded
        _uiState.update {
            it.copy(expanded = if (expanding) it.expanded + projectId else it.expanded - projectId)
        }
        // Load lazily the first time a project is expanded; keep the result cached afterwards.
        if (expanding && _uiState.value.content[projectId] == null) {
            loadContent(projectId)
        }
    }

    private fun loadContent(projectId: Long) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(content = it.content + (projectId to ReviewProjectContent(isLoading = true)))
            }
            val loaded = try {
                val parentTasks = taskRepository.getByProjectId(projectId).first().filter { !it.done }
                val subProjects = projectRepository.getChildren(projectId).first().map { child ->
                    ReviewSubProject(
                        project = child,
                        tasks = taskRepository.getByProjectId(child.id).first().filter { !it.done },
                    )
                }
                ReviewProjectContent(parentTasks, subProjects, isLoading = false)
            } catch (e: Exception) {
                ReviewProjectContent(isLoading = false)
            }
            _uiState.update { it.copy(content = it.content + (projectId to loaded)) }
        }
    }

    /** Refreshes the projects. Opening the screen refreshes quietly when offline; pulling does not. */
    fun refresh(manual: Boolean = true) {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null) }
            val result = projectRepository.refreshAll()
            _uiState.update { it.copy(isRefreshing = false, error = result.refreshErrorToShow(manual) ?: it.error) }
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    fun markReviewed(project: Project) {
        viewModelScope.launch {
            val meta = ReviewMetadata.parse(project.description)
                .let { ReviewMetadata(ReviewState.REVIEWED, dayClock.day.value.date.toString(), it.cadenceDaysOverride) }
            val updated = project.copy(description = ReviewMetadata.upsert(project.description, meta))
            reviewedThisSession.value = reviewedThisSession.value + project.id
            _uiState.update { it.copy(undo = project) }
            val result = projectRepository.update(updated)
            if (result is NetworkResult.Error) {
                // The review was not recorded: it must not look done, and there is nothing to undo.
                reviewedThisSession.value = reviewedThisSession.value - project.id
                _uiState.update { it.copy(undo = null, error = result.message) }
            }
        }
    }

    fun setCadence(project: Project, days: Int?) {
        viewModelScope.launch {
            val meta = ReviewMetadata.parse(project.description)
                .let { ReviewMetadata(it.state, it.lastReviewedAt, if (days != null && days > 0) days else null) }
            val updated = project.copy(description = ReviewMetadata.upsert(project.description, meta))
            reportFailure(projectRepository.update(updated))
        }
    }

    fun setExcluded(project: Project, excluded: Boolean) {
        viewModelScope.launch {
            val current = ReviewMetadata.parse(project.description)
            val meta = if (excluded) {
                ReviewMetadata(ReviewState.EXCLUDED, null, null)
            } else {
                ReviewMetadata(ReviewState.NEVER, null, current.cadenceDaysOverride)
            }
            val updated = project.copy(description = ReviewMetadata.upsert(project.description, meta))
            reportFailure(projectRepository.update(updated))
        }
    }

    fun undo() {
        val prev = _uiState.value.undo ?: return
        viewModelScope.launch {
            reviewedThisSession.value = reviewedThisSession.value - prev.id
            _uiState.update { it.copy(undo = null) }
            val result = projectRepository.update(prev)
            if (result is NetworkResult.Error) {
                // The undo did not happen: the project is still reviewed.
                reviewedThisSession.value = reviewedThisSession.value + prev.id
                _uiState.update { it.copy(error = result.message) }
            }
        }
    }

    private fun reportFailure(result: NetworkResult<*>) {
        if (result is NetworkResult.Error) _uiState.update { it.copy(error = result.message) }
    }

    fun dismissUndo() = _uiState.update { it.copy(undo = null) }
}
