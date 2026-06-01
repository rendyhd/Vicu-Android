package com.rendyhd.vicu.ui.screens.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.local.ReviewPrefs
import com.rendyhd.vicu.data.local.ReviewPrefsStore
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.util.ReviewMetadata
import com.rendyhd.vicu.util.ReviewState
import com.rendyhd.vicu.util.ReviewStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ReviewTab { DUE, ALL }

data class ReviewItem(
    val project: Project,
    val status: ReviewStatus,
)

data class ReviewUiState(
    val tab: ReviewTab = ReviewTab.DUE,
    val due: List<ReviewItem> = emptyList(),
    val all: List<ReviewItem> = emptyList(),
    val reviewedThisSession: Set<Long> = emptySet(),
    val enabled: Boolean = true,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val undo: Project? = null, // previous project state for the last mark-reviewed
    val error: String? = null,
)

@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val reviewPrefsStore: ReviewPrefsStore,
    private val authManager: AuthManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReviewUiState())
    val uiState: StateFlow<ReviewUiState> = _uiState.asStateFlow()

    private val inboxId = MutableStateFlow<Long?>(null)

    init {
        viewModelScope.launch { inboxId.value = authManager.getInboxProjectId() }
        viewModelScope.launch {
            combine(
                projectRepository.getAll(),
                reviewPrefsStore.getPrefs(),
                inboxId,
                _uiState,
            ) { projects, prefs, inbox, state ->
                buildState(projects, prefs, inbox, state)
            }.collect { built -> _uiState.value = built }
        }
        refresh()
    }

    private fun buildState(
        projects: List<Project>,
        prefs: ReviewPrefs,
        inbox: Long?,
        state: ReviewUiState,
    ): ReviewUiState {
        val tracked = projects
            .asSequence()
            .filterNot { it.isArchived }
            .filterNot { prefs.excludeInbox && inbox != null && it.id == inbox }
            .map {
                ReviewItem(
                    it,
                    ReviewMetadata.computeStatus(ReviewMetadata.parse(it.description), prefs.defaultCadenceDays),
                )
            }
            .filter { it.status.metadata.state != ReviewState.EXCLUDED }
            .sortedBy { it.status.daysUntilDue ?: Long.MIN_VALUE }
            .toList()
        val due = tracked.filter { it.status.isOverdue || it.project.id in state.reviewedThisSession }
        return state.copy(
            all = tracked,
            due = due,
            enabled = prefs.enabled,
            isLoading = false,
        )
    }

    fun setTab(tab: ReviewTab) = _uiState.update { it.copy(tab = tab) }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null) }
            try {
                projectRepository.refreshAll()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun markReviewed(project: Project) {
        viewModelScope.launch {
            val prev = project
            val meta = ReviewMetadata.parse(project.description)
                .let { ReviewMetadata(ReviewState.REVIEWED, ReviewMetadata.todayLocalIsoDate(), it.cadenceDaysOverride) }
            val updated = project.copy(description = ReviewMetadata.upsert(project.description, meta))
            _uiState.update {
                it.copy(reviewedThisSession = it.reviewedThisSession + project.id, undo = prev)
            }
            projectRepository.update(updated)
        }
    }

    fun setCadence(project: Project, days: Int?) {
        viewModelScope.launch {
            val meta = ReviewMetadata.parse(project.description)
                .let { ReviewMetadata(it.state, it.lastReviewedAt, if (days != null && days > 0) days else null) }
            val updated = project.copy(description = ReviewMetadata.upsert(project.description, meta))
            projectRepository.update(updated)
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
            projectRepository.update(updated)
        }
    }

    fun undo() {
        val prev = _uiState.value.undo ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(reviewedThisSession = it.reviewedThisSession - prev.id, undo = null)
            }
            projectRepository.update(prev)
        }
    }

    fun dismissUndo() = _uiState.update { it.copy(undo = null) }
}
