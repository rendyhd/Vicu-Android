package com.rendyhd.vicu.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.local.BottomBarPrefsStore
import com.rendyhd.vicu.data.local.DrawerView
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
import com.rendyhd.vicu.ui.screens.shared.ProjectActionResult
import com.rendyhd.vicu.ui.screens.shared.ProjectActions
import com.rendyhd.vicu.ui.screens.shared.SiblingMoveResult
import com.rendyhd.vicu.ui.screens.shared.orderNotSavedMessage
import com.rendyhd.vicu.util.AppDispatchers
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.ProjectCategory
import com.rendyhd.vicu.util.ProjectCategoryState
import com.rendyhd.vicu.util.ProjectProgress
import com.rendyhd.vicu.util.ReviewMetadata
import com.rendyhd.vicu.util.ReviewState
import com.rendyhd.vicu.util.projectProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
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
    /** The user's project categories (section 13); empty when there are none. */
    val categories: ProjectCategoryState = ProjectCategoryState.EMPTY,
    val collapsedCategoryIds: Set<String> = emptySet(),
    /** [projectRows] with the category headers between them: what the drawer lists. */
    val projectItems: List<ProjectListItem> = emptyList(),
    /** Every active project, the Inbox included: the tree leaves it out, the project pickers need it. */
    val allProjects: List<Project> = emptyList(),
    val labels: List<Label> = emptyList(),
    val customLists: List<CustomList> = emptyList(),
    val projectsExpanded: Boolean = true,
    val listsExpanded: Boolean = true,
    val tagsExpanded: Boolean = true,
    val bottomBarSlots: List<BottomBarSlot> = BottomBarSlot.DEFAULT_SLOTS,
    /** Whether the bottom bar draws the smart list icons in their identity colours (Settings). */
    val bottomBarSmartListColors: Boolean = true,
    val inboxProjectId: Long = 0L,
    val reviewEnabled: Boolean = true,
    val reviewOverdueCount: Int = 0,
    val routinesEnabled: Boolean = true,
    /** The views the user took out of the drawer (Settings, General, Drawer). */
    val hiddenViews: Set<DrawerView> = emptySet(),
    /** The day planner (Settings, General, Plan): off means no Plan entry (contract 16.9). */
    val plannerEnabled: Boolean = false,
) {
    /** What the drawer lists above the projects, in order: the [drawerViewChoices] the user did not hide. */
    fun views(askReady: Boolean): List<DrawerView> =
        drawerViewChoices(bottomBarSlots, routinesEnabled, reviewEnabled, askReady, plannerEnabled) - hiddenViews
}

/**
 * The views the drawer can list above the projects, in its order: Today, Upcoming and Anytime
 * while the bottom bar does not hold them, Plan as well while the planner is on, the Logbook,
 * Routines and Review while they are turned on, and Ask once it is set up. These are the ones
 * Settings offers to hide.
 */
internal fun drawerViewChoices(
    slots: List<BottomBarSlot>,
    routinesEnabled: Boolean,
    reviewEnabled: Boolean,
    askReady: Boolean,
    plannerEnabled: Boolean = false,
): List<DrawerView> {
    val inBar = slots.mapTo(HashSet()) { it.type }
    return buildList {
        if (BottomBarSlotType.TODAY !in inBar) add(DrawerView.TODAY)
        if (plannerEnabled && BottomBarSlotType.PLAN !in inBar) add(DrawerView.PLAN)
        if (BottomBarSlotType.UPCOMING !in inBar) add(DrawerView.UPCOMING)
        if (BottomBarSlotType.ANYTIME !in inBar) add(DrawerView.ANYTIME)
        add(DrawerView.LOGBOOK)
        if (routinesEnabled) add(DrawerView.ROUTINES)
        if (reviewEnabled) add(DrawerView.REVIEW)
        if (askReady) add(DrawerView.ASK)
    }
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class DrawerViewModel(
    projectRepository: ProjectRepository,
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
    private val projectActions: ProjectActions,
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

    /** Exposed for the app-root CompositionLocal that sets how much of a task a row shows. */
    val rowDensity: StateFlow<com.rendyhd.vicu.data.local.RowDensity> =
        behaviorPrefsStore.getPrefs()
            .map { it.rowDensity }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                com.rendyhd.vicu.data.local.RowDensity.COMPACT,
            )

    /** Whether the drawer shows progress rings (Settings); off, nothing is asked for them either. */
    private val showProgress: Flow<Boolean> = behaviorPrefsStore.getPrefs()
        .map { it.showProjectProgress }
        .distinctUntilChanged()

    private val _sectionsExpanded = MutableStateFlow(DrawerSectionsExpanded())

    private val sources: Flow<DrawerSources> = combine(
        combine(projectRepository.getAll(), projectActions.categories) { projects, categories -> projects to categories },
        labelRepository.getAll(),
        customListRepository.lists,
        _sectionsExpanded,
        authManager.inboxProjectId,
    ) { (projects, categories), labels, customLists, expanded, inboxId ->
        DrawerSources(projects, labels, customLists, expanded, inboxId, categories)
    }

    /**
     * What the repositories and the preferences hold. Built on the default dispatcher: the project
     * tree, the label order and the review badge (which parses every project's description) are
     * work for a worker thread, not the main one.
     */
    private val stored: StateFlow<DrawerUiState> = combine(
        sources,
        combine(
            bottomBarPrefsStore.config,
            behaviorPrefsStore.getPrefs().map { it.hiddenDrawerViews to it.plannerEnabled }.distinctUntilChanged(),
        ) { bottomBar, drawerPrefs -> bottomBar to drawerPrefs },
        combine(reviewPrefsStore.getPrefs(), routinePrefsStore.enabled) { review, routines -> review to routines },
        labelOrderPrefsStore.getOrder(),
        // A review that falls due at midnight must show up in the badge without a restart.
        dayClock.today,
    ) { sources, (bottomBar, drawerPrefs), (reviewPrefs, routinesEnabled), labelOrder, today ->
        buildDrawerState(
            sources, bottomBar.slots, reviewPrefs, labelOrder, today, routinesEnabled,
            bottomBarSmartListColors = bottomBar.smartListColors,
            hiddenViews = drawerPrefs.first,
            plannerEnabled = drawerPrefs.second,
        )
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
     * none while the cached count is fresh). Empty while the rings are turned off in Settings.
     */
    val projectProgress: StateFlow<Map<Long, ProjectProgress>> =
        combine(tallies, doneCounts, showProgress) { tallies, done, show ->
            if (!show) {
                emptyMap()
            } else {
                buildMap {
                    for ((projectId, count) in done) {
                        projectProgress(count, tallies?.get(projectId)?.open ?: 0)?.let { put(projectId, it) }
                    }
                }
            }
        }.flowOn(dispatchers.default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /**
     * How many open tasks each project with any has, from the local database (no request), while
     * the counts are turned on in Settings; empty otherwise. Independent of the rings.
     */
    val projectOpenCounts: StateFlow<Map<Long, Int>> =
        combine(
            tallies,
            behaviorPrefsStore.getPrefs().map { it.showProjectTaskCount }.distinctUntilChanged(),
        ) { tallies, show ->
            if (!show || tallies == null) {
                emptyMap()
            } else {
                buildMap { for ((projectId, tally) in tallies) if (tally.open > 0) put(projectId, tally.open) }
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
            // With the rings turned off the rows on screen ask for nothing.
            combine(progressRows, showProgress) { rows, show -> if (show) rows else emptySet() }
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

    /** The drawer's "New project": creates it and says how that went in the app-wide snackbar. */
    fun createProject(name: String, hexColor: String, parentProjectId: Long) {
        viewModelScope.launch {
            when (val result = projectActions.create(name, hexColor, parentProjectId)) {
                is ProjectActionResult.Done -> result.message?.let { appMessages.post(it) }
                is ProjectActionResult.Failed -> appMessages.post(result.error)
            }
        }
    }

    /** Opens or closes the projects below [projectId]. */
    fun toggleProjectCollapsed(projectId: Long) {
        _sectionsExpanded.update { sections ->
            val collapsed = sections.collapsedProjectIds
            sections.copy(collapsedProjectIds = if (projectId in collapsed) collapsed - projectId else collapsed + projectId)
        }
    }

    /** Opens or closes a category's projects; kept on this device only, as long as the app runs. */
    fun toggleCategoryCollapsed(categoryId: String) {
        _sectionsExpanded.update { sections ->
            val collapsed = sections.collapsedCategoryIds
            sections.copy(collapsedCategoryIds = if (categoryId in collapsed) collapsed - categoryId else collapsed + categoryId)
        }
    }

    /**
     * Saves the new order of the projects that share a level after a drag: [idsInNewOrder] is every
     * project of that level (for a top-level project, of its category's group), [movedId] the one
     * that was dragged. The positions are planned and saved by [ProjectActions.moveAmongSiblings]
     * (the Projects tab of Settings saves the same way). A refusal is reported and the list goes back
     * to what is stored.
     */
    fun reorderProject(movedId: Long, idsInNewOrder: List<Long>) {
        moveProject(movedId, stored.value.categories.categoryOf(movedId)?.id, idsInNewOrder)
    }

    /**
     * Saves a drop: [movedId] is now in the group of [categoryId] (null: the projects without a
     * category; it only counts for a top-level project), among [idsInNewOrder], every project of its
     * level there in the order shown. A drop into another category writes the project's category too
     * ([ProjectActions.moveIntoCategory]); into a collapsed one it goes last.
     */
    fun moveProject(movedId: Long, categoryId: String?, idsInNewOrder: List<Long>) {
        val state = stored.value
        val moved = state.projectRows.firstOrNull { it.project.id == movedId }
            ?: state.projectTree.firstOrNull { it.project.id == movedId }?.let { ProjectRow(it.project, 0, 0L, false, false) }
            ?: return
        val byId = state.allProjects.associateBy { it.id }
        val topLevel = moved.parentId == 0L
        val fromCategory = state.categories.categoryOf(movedId)?.id
        val intoOther = topLevel && categoryId != fromCategory
        var ids = idsInNewOrder.distinct()
        if (intoOther && categoryId != null && categoryId in state.collapsedCategoryIds) {
            ids = topLevelGroupIds(state, categoryId).filter { it != movedId } + movedId
        }
        val ordered = ids.mapNotNull { byId[it] }
        val newOrder = ordered.map { it.id }
        if (movedId !in newOrder) return
        val unchanged = if (topLevel) newOrder == topLevelGroupIds(state, fromCategory) else newOrder == siblingIds(state.projectRows, movedId)
        if (!intoOther && unchanged) return

        pendingOrder.update {
            it.copy(projectParentId = moved.parentId, projectIds = newOrder, movedCategory = if (intoOther) movedId to categoryId else null)
        }
        viewModelScope.launch {
            reorderMutex.withLock {
                val result = if (intoOther) {
                    projectActions.moveIntoCategory(movedId, categoryId, ordered)
                } else {
                    projectActions.moveAmongSiblings(movedId, ordered)
                }
                when (result) {
                    SiblingMoveResult.Saved -> awaitStored {
                        if (topLevel) {
                            it.categories.categoryOf(movedId)?.id == categoryId && topLevelGroupIds(it, categoryId) == newOrder
                        } else {
                            siblingIds(it.projectRows, movedId) == newOrder
                        }
                    }
                    is SiblingMoveResult.Failed -> appMessages.post(result.message)
                    SiblingMoveResult.NotMoved -> Unit
                }
                pendingOrder.update { if (it.projectIds == newOrder) it.copy(projectIds = null, movedCategory = null) else it }
            }
        }
    }

    // --- Categories: the header's menu --------------------------------------------------------

    fun renameCategory(categoryId: String, name: String) {
        viewModelScope.launch { postIfFailed(projectActions.renameCategory(categoryId, name)) }
    }

    /** Moves a category one place up (-1) or down (1). */
    fun moveCategory(categoryId: String, by: Int) {
        viewModelScope.launch { postIfFailed(projectActions.moveCategory(categoryId, by)) }
    }

    /** Deletes [category] (its projects stay) and offers to undo it. */
    fun deleteCategory(category: ProjectCategory) {
        viewModelScope.launch { projectActions.deleteCategoryOfferingUndo(category) }
    }

    private fun postIfFailed(result: ProjectActionResult) {
        if (result is ProjectActionResult.Failed) appMessages.post(result.error)
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

    private fun orderNotSaved(detail: String) = orderNotSavedMessage(detail)
}

/** An order the user has just dropped, not yet in the stored state. A null field has nothing pending. */
internal data class PendingDrawerOrder(
    /** The project the reordered projects are below, 0 for the roots. */
    val projectParentId: Long = 0L,
    val projectIds: List<Long>? = null,
    /** A top-level project dropped into another category's group, and that category (null: none). */
    val movedCategory: Pair<Long, String?>? = null,
    val listIds: List<String>? = null,
    val labelIds: List<Long>? = null,
)

/** This state with the pending order laid over what is stored. The same state when nothing is pending. */
internal fun DrawerUiState.withPendingOrder(pending: PendingDrawerOrder): DrawerUiState {
    if (pending.projectIds == null && pending.listIds == null && pending.labelIds == null) return this
    var result = this
    pending.projectIds?.let { ids ->
        // Other groups keep their own order: the categories regroup the roots after this.
        val tree = reorderSiblings(projectTree, pending.projectParentId, ids)
        val categories = pending.movedCategory?.let { (projectId, categoryId) -> categories.withAssignment(projectId, categoryId) } ?: categories
        val items = projectListItems(tree, collapsedProjectIds, categories, collapsedCategoryIds)
        result = result.copy(projectTree = tree, categories = categories, projectItems = items, projectRows = items.projectRows())
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
    /** Categories whose projects are hidden; every category starts open. */
    val collapsedCategoryIds: Set<String> = emptySet(),
)

/** What the repositories and the section state hand the drawer: one typed value instead of casts out of a list. */
internal data class DrawerSources(
    val projects: List<Project>,
    val labels: List<Label>,
    val customLists: List<CustomList>,
    val expanded: DrawerSectionsExpanded,
    val inboxProjectId: Long?,
    val categories: ProjectCategoryState = ProjectCategoryState.EMPTY,
)

/** The drawer's state: pure, so it can run on any thread and be tested without a view model. */
internal fun buildDrawerState(
    sources: DrawerSources,
    slots: List<BottomBarSlot>,
    reviewPrefs: ReviewPrefs,
    labelOrder: List<Long>,
    today: LocalDate,
    routinesEnabled: Boolean = true,
    bottomBarSmartListColors: Boolean = true,
    hiddenViews: Set<DrawerView> = emptySet(),
    plannerEnabled: Boolean = false,
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
    val collapsedCategories = sources.expanded.collapsedCategoryIds
    val items = projectListItems(tree, collapsed, sources.categories, collapsedCategories)

    return DrawerUiState(
        projectTree = tree,
        projectRows = items.projectRows(),
        collapsedProjectIds = collapsed,
        categories = sources.categories,
        collapsedCategoryIds = collapsedCategories,
        projectItems = items,
        allProjects = active,
        labels = applyLabelOrder(sources.labels, labelOrder),
        customLists = sources.customLists,
        projectsExpanded = sources.expanded.projects,
        listsExpanded = sources.expanded.lists,
        tagsExpanded = sources.expanded.tags,
        bottomBarSlots = slots,
        bottomBarSmartListColors = bottomBarSmartListColors,
        inboxProjectId = inboxId ?: 0L,
        reviewEnabled = reviewPrefs.enabled,
        reviewOverdueCount = reviewOverdue,
        routinesEnabled = routinesEnabled,
        hiddenViews = hiddenViews,
        plannerEnabled = plannerEnabled,
    )
}

/** The top-level projects in the group of [categoryId] (null: the ones without a category), in display order. */
internal fun topLevelGroupIds(state: DrawerUiState, categoryId: String?): List<Long> =
    state.categories.group(state.projectTree) { it.project.id }
        .firstOrNull { it.category?.id == categoryId }
        ?.items?.map { it.project.id }
        .orEmpty()

/** Orders [labels] by the stored client-side [order]; ids not present fall back to A to Z at the end. */
private fun applyLabelOrder(labels: List<Label>, order: List<Long>): List<Label> {
    if (order.isEmpty()) return labels.sortedBy { it.title.lowercase() }
    val byId = labels.associateBy { it.id }
    val ordered = order.mapNotNull { byId[it] }
    val remaining = labels.filterNot { it.id in order }.sortedBy { it.title.lowercase() }
    return ordered + remaining
}
