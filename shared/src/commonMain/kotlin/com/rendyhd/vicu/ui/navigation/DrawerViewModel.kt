package com.rendyhd.vicu.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.local.BottomBarPrefsStore
import com.rendyhd.vicu.data.local.LabelOrderPrefsStore
import com.rendyhd.vicu.data.local.ReviewPrefs
import com.rendyhd.vicu.data.local.ReviewPrefsStore
import com.rendyhd.vicu.data.local.RoutinePrefsStore
import com.rendyhd.vicu.domain.model.BottomBarSlot
import com.rendyhd.vicu.domain.model.BottomBarSlotType
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.ProjectTally
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectProgressSource
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.CustomListRepository
import com.rendyhd.vicu.util.AppDispatchers
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.DropPlan
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.PositionedId
import com.rendyhd.vicu.util.ProjectProgress
import com.rendyhd.vicu.util.ReviewMetadata
import com.rendyhd.vicu.util.ReviewState
import com.rendyhd.vicu.util.planDropAmong
import com.rendyhd.vicu.util.projectProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.LocalDate

/** How long a drop is shown ahead of the stored order before the stored order is trusted again. */
internal const val PENDING_ORDER_TIMEOUT_MS = 2_000L

/** Changes to a project's tasks are settled for this long before its progress ring asks the server again. */
internal const val PROGRESS_SETTLE_MS = 250L

/**
 * A ring whose count could not be read is asked for again after this long while the drawer stays
 * open (a little over the source's own memory of a failure, which answers null until it passes).
 */
internal const val PROGRESS_RETRY_MS = 61_000L

data class DrawerUiState(
    /** The projects to list (no Inbox, nothing archived) as a tree, at any depth. */
    val projectTree: List<ProjectNode> = emptyList(),
    /** What the drawer shows of that tree: in display order, without what is below a collapsed project. */
    val projectRows: List<ProjectRow> = emptyList(),
    val collapsedProjectIds: Set<Long> = emptySet(),
    /** Every active project, the Inbox included: the tree leaves it out, the project pickers need it. */
    val allProjects: List<Project> = emptyList(),
    val labels: List<Label> = emptyList(),
    val customLists: List<CustomList> = emptyList(),
    val projectsExpanded: Boolean = true,
    val listsExpanded: Boolean = true,
    val tagsExpanded: Boolean = true,
    val bottomBarSlots: List<BottomBarSlot> = BottomBarSlot.DEFAULT_SLOTS,
    val inboxProjectId: Long = 0L,
    val reviewEnabled: Boolean = true,
    val reviewOverdueCount: Int = 0,
    val routinesEnabled: Boolean = true,
) {
    val displacedSmartLists: Set<BottomBarSlotType>
        get() {
            val inBar = bottomBarSlots.map { it.type }.toSet()
            return setOf(
                BottomBarSlotType.TODAY,
                BottomBarSlotType.UPCOMING,
                BottomBarSlotType.ANYTIME,
            ) - inBar
        }
}

class DrawerViewModel(
    private val projectRepository: ProjectRepository,
    labelRepository: LabelRepository,
    private val customListRepository: CustomListRepository,
    private val authManager: AuthManager,
    private val bottomBarPrefsStore: BottomBarPrefsStore,
    private val reviewPrefsStore: ReviewPrefsStore,
    routinePrefsStore: RoutinePrefsStore,
    private val labelOrderPrefsStore: LabelOrderPrefsStore,
    behaviorPrefsStore: com.rendyhd.vicu.data.local.BehaviorPrefsStore,
    dayClock: com.rendyhd.vicu.util.DayClock,
    dispatchers: AppDispatchers,
    private val appMessages: AppMessages,
    private val progressSource: ProjectProgressSource,
) : ViewModel() {

    /** Exposed for the app-root CompositionLocal that positions the FAB. */
    val fabAlignStart: StateFlow<Boolean> = behaviorPrefsStore.getPrefs()
        .map { it.fabAlignStart }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Exposed for the app-root CompositionLocal that controls inline subtask rendering. */
    val subtaskDisplayMode: StateFlow<com.rendyhd.vicu.data.local.SubtaskDisplayMode> =
        behaviorPrefsStore.getPrefs()
            .map { it.subtaskDisplayMode }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                com.rendyhd.vicu.data.local.SubtaskDisplayMode.INSIDE_TASK,
            )

    private val _sectionsExpanded = MutableStateFlow(DrawerSectionsExpanded())

    private val sources: Flow<DrawerSources> = combine(
        projectRepository.getAll(),
        labelRepository.getAll(),
        customListRepository.lists,
        _sectionsExpanded,
        authManager.inboxProjectId,
    ) { projects, labels, customLists, expanded, inboxId ->
        DrawerSources(projects, labels, customLists, expanded, inboxId)
    }

    /**
     * What the repositories and the preferences hold. Built on the default dispatcher: the project
     * tree, the label order and the review badge (which parses every project's description) are
     * work for a worker thread, not the main one.
     */
    private val stored: StateFlow<DrawerUiState> = combine(
        sources,
        bottomBarPrefsStore.slots,
        combine(reviewPrefsStore.getPrefs(), routinePrefsStore.enabled) { review, routines -> review to routines },
        labelOrderPrefsStore.getOrder(),
        // A review that falls due at midnight must show up in the badge without a restart.
        dayClock.today,
    ) { sources, slots, (reviewPrefs, routinesEnabled), labelOrder, today ->
        buildDrawerState(sources, slots, reviewPrefs, labelOrder, today, routinesEnabled)
    }.flowOn(dispatchers.default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DrawerUiState())

    // --- Project progress rings (decision 11) ---------------------------------------------------

    /** The projects whose rows are on screen while the drawer is open; nothing is asked for any other. */
    private val progressRows = MutableStateFlow<Set<Long>>(emptySet())

    /** The open and cached done tasks per project; null until the first read. */
    private val tallies: StateFlow<Map<Long, ProjectTally>?> = progressSource.observeTallies()
        .flowOn(dispatchers.default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** The done tasks of a project as the server last said, hidden carriers left out. */
    private val doneCounts = MutableStateFlow<Map<Long, Long>>(emptyMap())

    /** What was asked for each project since the drawer was last opened: ask again only when it changes. */
    private val asked = HashMap<Long, ProjectTally>()

    /** Bumped to look at the rows again without any change to the tasks (a failed read is due a retry). */
    private val progressRecheck = MutableStateFlow(0)
    private var progressRetry: Job? = null
    private var seenInvalidations = 0

    /**
     * The progress ring of every project that has one: done tasks out of all of them. The open
     * side follows the local database, the done side is one cached request per project, made
     * only for the rows on screen while the drawer is open (at most one request per project, and
     * none while the cached count is fresh).
     */
    val projectProgress: StateFlow<Map<Long, ProjectProgress>> =
        combine(tallies, doneCounts) { tallies, done ->
            buildMap {
                for ((projectId, count) in done) {
                    projectProgress(count, tallies?.get(projectId)?.open ?: 0)?.let { put(projectId, it) }
                }
            }
        }.flowOn(dispatchers.default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** The projects with a row on screen while the drawer is open; empty when it is closed. */
    fun setProgressRows(projectIds: Set<Long>) {
        if (projectIds.isEmpty()) asked.clear()
        progressRows.value = projectIds
    }

    /** One retry timer at a time, however many rings failed. */
    private fun scheduleProgressRetry() {
        if (progressRetry?.isActive == true) return
        progressRetry = viewModelScope.launch {
            delay(PROGRESS_RETRY_MS)
            progressRecheck.update { it + 1 }
        }
    }

    init {
        viewModelScope.launch {
            progressRows
                .flatMapLatest { rows ->
                    if (rows.isEmpty()) {
                        emptyFlow()
                    } else {
                        // The settle delay applies to changes of the tasks only; a sync that sent
                        // something, or a retry, looks again at once.
                        combine(
                            tallies.filterNotNull().debounce(PROGRESS_SETTLE_MS),
                            progressSource.invalidations(),
                            progressRecheck,
                        ) { tallies, invalidations, _ -> Triple(rows, tallies, invalidations) }
                    }
                }
                .collect { (rows, tallies, invalidations) ->
                    // Everything asked before a sync that sent changes is stale: ask for it all again.
                    if (invalidations != seenInvalidations) {
                        seenInvalidations = invalidations
                        asked.clear()
                    }
                    for (projectId in rows) {
                        val tally = tallies[projectId] ?: ProjectTally.EMPTY
                        if (asked[projectId] == tally) continue
                        asked[projectId] = tally
                        launch {
                            val count = try {
                                progressSource.doneCount(projectId, tally)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                null
                            }
                            if (count != null) {
                                doneCounts.update { it + (projectId to count) }
                            } else if (asked[projectId] == tally) {
                                // Not read (offline, server trouble): forget the question so the next
                                // look, a retry or a change, asks again instead of keeping a missing ring.
                                asked.remove(projectId)
                                scheduleProgressRetry()
                            }
                        }
                    }
                }
        }
    }

    /** An order the user has just dropped, shown until the stored order shows it too. */
    private val pendingOrder = MutableStateFlow(PendingDrawerOrder())

    /** Drops are saved one at a time, so a quick second drop cannot overtake the first. */
    private val reorderMutex = Mutex()

    /**
     * [stored] with the order just dropped laid over it: the repositories take a moment to say so,
     * and the row must not jump back to its old place in between. The overlay is cheap (it only
     * reorders what is already there), so it runs on the main thread, in step with the drop.
     */
    val uiState: StateFlow<DrawerUiState> = combine(stored, pendingOrder) { state, pending ->
        state.withPendingOrder(pending)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DrawerUiState())

    fun toggleProjectsExpanded() {
        _sectionsExpanded.update { it.copy(projects = !it.projects) }
    }

    fun toggleListsExpanded() {
        _sectionsExpanded.update { it.copy(lists = !it.lists) }
    }

    fun toggleTagsExpanded() {
        _sectionsExpanded.update { it.copy(tags = !it.tags) }
    }

    fun saveCustomList(customList: CustomList) {
        viewModelScope.launch {
            customListRepository.upsert(customList)
        }
    }

    /** Opens or closes the projects below [projectId]. */
    fun toggleProjectCollapsed(projectId: Long) {
        _sectionsExpanded.update { sections ->
            val collapsed = sections.collapsedProjectIds
            sections.copy(collapsedProjectIds = if (projectId in collapsed) collapsed - projectId else collapsed + projectId)
        }
    }

    /**
     * Saves the new order of the projects that share a level after a drag: [idsInNewOrder] is every
     * project of that level, [movedId] the one that was dragged. The positions are planned like the
     * desktop app's (one position between the neighbours, or a renumbering when they leave no room).
     * A refusal is reported and the list goes back to what is stored.
     */
    fun reorderProject(movedId: Long, idsInNewOrder: List<Long>) {
        val state = stored.value
        val moved = state.projectRows.firstOrNull { it.project.id == movedId } ?: return
        val byId = state.allProjects.associateBy { it.id }
        val ordered = idsInNewOrder.distinct().mapNotNull { byId[it] }
        val newOrder = ordered.map { it.id }
        if (movedId !in newOrder || newOrder == siblingIds(state.projectRows, movedId)) return
        val plan = planDropAmong(ordered.map { PositionedId(it.id, it.position) }, movedId) ?: return

        pendingOrder.update { it.copy(projectParentId = moved.parentId, projectIds = newOrder) }
        viewModelScope.launch {
            reorderMutex.withLock {
                val failure = saveProjectPositions(plan, byId)
                if (failure == null) {
                    awaitStored { siblingIds(it.projectRows, movedId) == newOrder }
                } else {
                    appMessages.post(orderNotSaved(failure))
                }
                pendingOrder.update { if (it.projectIds == newOrder) it.copy(projectIds = null) else it }
            }
        }
    }

    /** The first refusal's message, or null when every position was saved (or queued for later). */
    private suspend fun saveProjectPositions(plan: DropPlan, byId: Map<Long, Project>): String? {
        for (update in plan.updates) {
            val project = byId[update.id] ?: continue
            val result = projectRepository.update(project.copy(position = update.position))
            if (result is NetworkResult.Error) return result.message
        }
        return null
    }

    /** Saves the new order of the custom lists after a drag: [idsInNewOrder] is every list, [movedId] the dragged one. */
    fun reorderCustomList(movedId: String, idsInNewOrder: List<String>) {
        val current = stored.value.customLists.map { it.id }
        val newOrder = idsInNewOrder.distinct().filter { it in current }
        val from = current.indexOf(movedId)
        val to = newOrder.indexOf(movedId)
        if (from < 0 || to < 0 || newOrder == current) return

        pendingOrder.update { it.copy(listIds = newOrder) }
        viewModelScope.launch {
            reorderMutex.withLock {
                if (saved { customListRepository.reorder(from, to) }) {
                    awaitStored { state -> state.customLists.map { it.id } == newOrder }
                }
                pendingOrder.update { if (it.listIds == newOrder) it.copy(listIds = null) else it }
            }
        }
    }

    /** Saves the new order of the labels after a drag: [idsInNewOrder] is every label, [movedId] the dragged one. */
    fun reorderLabel(movedId: Long, idsInNewOrder: List<Long>) {
        val current = stored.value.labels.map { it.id }
        val newOrder = idsInNewOrder.distinct().filter { it in current }
        if (movedId !in newOrder || newOrder == current) return

        pendingOrder.update { it.copy(labelIds = newOrder) }
        viewModelScope.launch {
            reorderMutex.withLock {
                if (saved { labelOrderPrefsStore.setOrder(newOrder) }) {
                    awaitStored { state -> state.labels.map { it.id } == newOrder }
                }
                pendingOrder.update { if (it.labelIds == newOrder) it.copy(labelIds = null) else it }
            }
        }
    }

    /** Runs [save]; a failure is reported and answers false. */
    private suspend fun saved(save: suspend () -> Unit): Boolean = try {
        save()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        appMessages.post(orderNotSaved(""))
        false
    }

    /** Waits (for a while) until the stored state [matches]: the order just dropped can then be let go. */
    private suspend fun awaitStored(matches: (DrawerUiState) -> Boolean) {
        withTimeoutOrNull(PENDING_ORDER_TIMEOUT_MS) { stored.first(matches) }
    }

    private fun orderNotSaved(detail: String) =
        if (detail.isBlank()) "Could not save the new order" else "Could not save the new order: $detail"
}

/** An order the user has just dropped, not yet in the stored state. A null field has nothing pending. */
internal data class PendingDrawerOrder(
    /** The project the reordered projects are below, 0 for the roots. */
    val projectParentId: Long = 0L,
    val projectIds: List<Long>? = null,
    val listIds: List<String>? = null,
    val labelIds: List<Long>? = null,
)

/** This state with the pending order laid over what is stored. The same state when nothing is pending. */
internal fun DrawerUiState.withPendingOrder(pending: PendingDrawerOrder): DrawerUiState {
    if (pending.projectIds == null && pending.listIds == null && pending.labelIds == null) return this
    var result = this
    pending.projectIds?.let { ids ->
        val tree = reorderSiblings(projectTree, pending.projectParentId, ids)
        result = result.copy(projectTree = tree, projectRows = visibleProjectRows(tree, collapsedProjectIds))
    }
    pending.listIds?.let { ids -> result = result.copy(customLists = orderedBy(customLists, ids) { it.id }) }
    pending.labelIds?.let { ids -> result = result.copy(labels = orderedBy(labels, ids) { it.id }) }
    return result
}

/** Which of the drawer's sections are open. */
data class DrawerSectionsExpanded(
    val projects: Boolean = true,
    val lists: Boolean = true,
    val tags: Boolean = true,
    /** Projects whose children are hidden; every project starts open. */
    val collapsedProjectIds: Set<Long> = emptySet(),
)

/** What the repositories and the section state hand the drawer: one typed value instead of casts out of a list. */
internal data class DrawerSources(
    val projects: List<Project>,
    val labels: List<Label>,
    val customLists: List<CustomList>,
    val expanded: DrawerSectionsExpanded,
    val inboxProjectId: Long?,
)

/** The drawer's state: pure, so it can run on any thread and be tested without a view model. */
internal fun buildDrawerState(
    sources: DrawerSources,
    slots: List<BottomBarSlot>,
    reviewPrefs: ReviewPrefs,
    labelOrder: List<Long>,
    today: LocalDate,
    routinesEnabled: Boolean = true,
): DrawerUiState {
    val inboxId = sources.inboxProjectId
    val projects = sources.projects

    // The badge is only worked out when reviews are on: it parses every project's description.
    val reviewOverdue = if (reviewPrefs.enabled) {
        projects
            .asSequence()
            .filterNot { it.isArchived }
            .filterNot { reviewPrefs.excludeInbox && inboxId != null && it.id == inboxId }
            .map {
                ReviewMetadata.computeStatus(ReviewMetadata.parse(it.description), reviewPrefs.defaultCadenceDays, today)
            }
            .filter { it.metadata.state != ReviewState.EXCLUDED }
            .count { it.isOverdue }
    } else {
        0
    }

    // The Inbox has its own place in the drawer and the bar, so the tree leaves it out; the project
    // pickers (a custom list's projects) still need it.
    val active = projects.filter { !it.isArchived }
    val tree = buildProjectTree(active.filter { it.id != inboxId })
    val collapsed = sources.expanded.collapsedProjectIds

    return DrawerUiState(
        projectTree = tree,
        projectRows = visibleProjectRows(tree, collapsed),
        collapsedProjectIds = collapsed,
        allProjects = active,
        labels = applyLabelOrder(sources.labels, labelOrder),
        customLists = sources.customLists,
        projectsExpanded = sources.expanded.projects,
        listsExpanded = sources.expanded.lists,
        tagsExpanded = sources.expanded.tags,
        bottomBarSlots = slots,
        inboxProjectId = inboxId ?: 0L,
        reviewEnabled = reviewPrefs.enabled,
        reviewOverdueCount = reviewOverdue,
        routinesEnabled = routinesEnabled,
    )
}

/** Orders [labels] by the stored client-side [order]; ids not present fall back to A to Z at the end. */
private fun applyLabelOrder(labels: List<Label>, order: List<Long>): List<Label> {
    if (order.isEmpty()) return labels.sortedBy { it.title.lowercase() }
    val byId = labels.associateBy { it.id }
    val ordered = order.mapNotNull { byId[it] }
    val remaining = labels.filterNot { it.id in order }.sortedBy { it.title.lowercase() }
    return ordered + remaining
}
