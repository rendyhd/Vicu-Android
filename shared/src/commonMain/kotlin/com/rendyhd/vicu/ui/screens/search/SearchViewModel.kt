package com.rendyhd.vicu.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.data.sync.refreshErrorToShow
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.ui.navigation.NavigationTicker
import com.rendyhd.vicu.ui.screens.shared.CompletionHold
import com.rendyhd.vicu.ui.screens.shared.CompletionToast
import com.rendyhd.vicu.ui.screens.shared.NoCompletionToast
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.ui.screens.shared.SEARCH_REFRESH_DEBOUNCE_MS
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.SearchFilters
import com.rendyhd.vicu.util.SystemTimeSource
import com.rendyhd.vicu.util.TimeSource
import com.rendyhd.vicu.util.withoutNestedSubtasks
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.datetime.toLocalDateTime
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val filters: SearchFilters = SearchFilters(),
    /** Open tasks that match [query]. */
    val results: List<Task> = emptyList(),
    /** Completed tasks that match [query]: a section of their own below the open ones. */
    val completedResults: List<Task> = emptyList(),
    /** True once the cached matches of [query] have been read; until then there is nothing to say about them. */
    val resultsReady: Boolean = false,
    /** True from a new text until the server has answered it (or could not): the cached matches are on screen meanwhile. */
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val completedTaskIds: Set<Long> = emptySet(),
)

/**
 * Search reads Room first: the cached tasks whose title or description contains what was typed
 * show at once, open ones and completed ones in separate sections. The server is asked in the
 * background after the text has rested and only updates Room, which the list follows. The results
 * of the previous text are dropped as soon as the text changes (A-UI-16). The filters (contract
 * section 17) narrow the same matches, also without any text, and last as long as this view model.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val labelRepository: LabelRepository,
    navigationTicker: NavigationTicker = NavigationTicker(),
    completionToast: CompletionToast = NoCompletionToast,
    private val time: TimeSource = SystemTimeSource,
) : ViewModel() {

    /** The projects, so a row can name its own: the results mix them without headers. */
    val projects: StateFlow<List<Project>> = projectRepository.getAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** The labels the Labels filter offers. */
    val labels: StateFlow<List<Label>> = labelRepository.getAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    /** Rows completed on this screen, kept in place for a moment (see [CompletionHold]). */
    val completions = CompletionHold(viewModelScope, navigationTicker = navigationTicker, toast = completionToast)

    /** What was typed, as typed. */
    private val typed = MutableStateFlow("")

    private val filters = MutableStateFlow(SearchFilters())

    /** The search as it stands; nothing to look for without text and without a filter. */
    private val search = combine(typed.map { it.trim() }, filters) { text, set -> text to set }.distinctUntilChanged()

    /** The cached matches of [text]; the search rides along so an answer for an old one can be told from the current one. */
    private data class Matches(val text: String, val filters: SearchFilters, val open: List<Task>, val completed: List<Task>)

    init {
        viewModelScope.launch {
            completions.heldIds.collect { ids -> _uiState.update { it.copy(completedTaskIds = ids) } }
        }

        // What is on screen: the cached matches of the current text, from the first keystroke on.
        // flatMapLatest drops the previous text's collection, so its results cannot come back.
        viewModelScope.launch {
            search
                .flatMapLatest { (text, set) ->
                    if (text.isEmpty() && !set.isActive) flowOf(Matches("", set, emptyList(), emptyList())) else cachedMatches(text, set)
                }
                .collect { matches ->
                    // An emission that was already on its way for the previous search is not shown.
                    if (matches.text != typed.value.trim() || matches.filters != filters.value) return@collect
                    _uiState.update {
                        it.copy(results = matches.open, completedResults = matches.completed, resultsReady = true)
                    }
                }
        }

        // The server, in the background: debounced, one request at a time, cancelled by a new text.
        viewModelScope.launch {
            search.collectLatest { (text, set) ->
                if (text.isEmpty() && !set.isActive) {
                    _uiState.update { it.copy(isRefreshing = false) }
                    return@collectLatest
                }
                delay(SEARCH_REFRESH_DEBOUNCE_MS)
                _uiState.update { it.copy(isRefreshing = true) }
                try {
                    val zone = time.zone()
                    val today = time.now().toLocalDateTime(zone).date
                    val refreshed = taskRepository.refreshAll(set.serverParams(text, today, zone))
                    // Offline stays quiet (the cached matches are shown); other failures are reported.
                    refreshed.refreshErrorToShow(manual = false)?.let { message ->
                        _uiState.update { it.copy(error = message) }
                    }
                } finally {
                    // A newer search has already marked the search as running again.
                    if (typed.value.trim() == text && filters.value == set) _uiState.update { it.copy(isRefreshing = false) }
                }
            }
        }
    }

    private fun cachedMatches(text: String, set: SearchFilters): Flow<Matches> = combine(
        if (text.isEmpty()) taskRepository.getAllTasksFlat() else taskRepository.searchTasks(text),
        projectRepository.getAll(),
        completions.state,
    ) { tasks, projects, held ->
        val activeIds = projects.mapTo(HashSet()) { it.id }
        val zone = time.zone()
        val today = time.now().toLocalDateTime(zone).date
        // Nest what matched: a matching subtask shows by itself when its parent did not match.
        val matched = tasks
            .filter { it.projectId in activeIds && set.matches(it, today, zone) }
            .withoutNestedSubtasks(hideChildrenOfCompletedParents = false)
        Matches(
            text = text,
            filters = set,
            open = completions.merge(matched.filter { !it.done }),
            // A row just completed here stays in the open section for a moment instead.
            completed = matched.filter { it.done && it.id !in held },
        )
    }

    fun onQueryChanged(query: String) {
        val sameSearch = query.trim() == typed.value.trim()
        if (!sameSearch) {
            // Rows held from the previous query do not belong to the new one, nor do its results.
            completions.releaseAll()
        }
        _uiState.update {
            if (sameSearch) {
                it.copy(query = query)
            } else {
                it.copy(
                    query = query,
                    results = emptyList(),
                    completedResults = emptyList(),
                    resultsReady = false,
                    // The server is asked once the text rests: until then the search is not over.
                    isRefreshing = query.isNotBlank() || filters.value.isActive,
                )
            }
        }
        typed.value = query
    }

    fun onFiltersChanged(set: SearchFilters) {
        if (set == filters.value) return
        completions.releaseAll()
        _uiState.update {
            it.copy(
                filters = set,
                results = emptyList(),
                completedResults = emptyList(),
                resultsReady = false,
                isRefreshing = it.query.isNotBlank() || set.isActive,
            )
        }
        filters.value = set
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

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
