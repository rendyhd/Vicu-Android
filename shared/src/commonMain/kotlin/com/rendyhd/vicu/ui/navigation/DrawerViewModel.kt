package com.rendyhd.vicu.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.local.BottomBarPrefsStore
import com.rendyhd.vicu.data.local.LabelOrderPrefsStore
import com.rendyhd.vicu.data.local.ReviewPrefs
import com.rendyhd.vicu.data.local.ReviewPrefsStore
import com.rendyhd.vicu.domain.model.BottomBarSlot
import com.rendyhd.vicu.domain.model.BottomBarSlotType
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.CustomListRepository
import com.rendyhd.vicu.util.AppDispatchers
import com.rendyhd.vicu.util.ReviewMetadata
import com.rendyhd.vicu.util.ReviewState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

data class ProjectNode(
    val project: Project,
    val children: List<Project>,
)

data class DrawerUiState(
    val projectTree: List<ProjectNode> = emptyList(),
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
    private val labelOrderPrefsStore: LabelOrderPrefsStore,
    behaviorPrefsStore: com.rendyhd.vicu.data.local.BehaviorPrefsStore,
    dayClock: com.rendyhd.vicu.util.DayClock,
    dispatchers: AppDispatchers,
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
     * Built on the default dispatcher: the project tree, the label order and the review badge
     * (which parses every project's description) are work for a worker thread, not the main one.
     */
    val uiState: StateFlow<DrawerUiState> = combine(
        sources,
        bottomBarPrefsStore.slots,
        reviewPrefsStore.getPrefs(),
        labelOrderPrefsStore.getOrder(),
        // A review that falls due at midnight must show up in the badge without a restart.
        dayClock.today,
    ) { sources, slots, reviewPrefs, labelOrder, today ->
        buildDrawerState(sources, slots, reviewPrefs, labelOrder, today)
    }.flowOn(dispatchers.default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DrawerUiState())

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

    fun reorderProject(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            val roots = uiState.value.projectTree.map { it.project }
            if (fromIndex == toIndex || fromIndex !in roots.indices || toIndex !in roots.indices) return@launch
            val mutable = roots.toMutableList()
            val moved = mutable.removeAt(fromIndex)
            mutable.add(toIndex, moved)
            val newPos = when {
                mutable.size == 1 -> 1.0
                toIndex == 0 -> (mutable.getOrNull(1)?.position ?: 1.0) / 2.0
                toIndex == mutable.lastIndex -> mutable[toIndex - 1].position + 1.0
                else -> (mutable[toIndex - 1].position + mutable[toIndex + 1].position) / 2.0
            }
            projectRepository.update(moved.copy(position = newPos))
        }
    }

    fun reorderCustomList(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            customListRepository.reorder(fromIndex, toIndex)
        }
    }

    fun reorderLabel(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            val current = uiState.value.labels
            if (fromIndex == toIndex || fromIndex !in current.indices || toIndex !in current.indices) {
                return@launch
            }
            val mutable = current.toMutableList()
            val moved = mutable.removeAt(fromIndex)
            mutable.add(toIndex, moved)
            labelOrderPrefsStore.setOrder(mutable.map { it.id })
        }
    }
}

/** Which of the drawer's sections are open. */
data class DrawerSectionsExpanded(
    val projects: Boolean = true,
    val lists: Boolean = true,
    val tags: Boolean = true,
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

    val nonArchived = projects.filter { !it.isArchived && it.id != inboxId }
    val activeIds = nonArchived.mapTo(mutableSetOf()) { it.id }
    val roots = nonArchived
        .filter { it.parentProjectId == 0L || it.parentProjectId !in activeIds }
        .sortedBy { it.position }
    val childMap = nonArchived
        .filter { it.parentProjectId != 0L }
        .groupBy { it.parentProjectId }

    val tree = roots.map { root ->
        ProjectNode(
            project = root,
            children = (childMap[root.id] ?: emptyList()).sortedBy { it.position },
        )
    }

    return DrawerUiState(
        projectTree = tree,
        allProjects = nonArchived,
        labels = applyLabelOrder(sources.labels, labelOrder),
        customLists = sources.customLists,
        projectsExpanded = sources.expanded.projects,
        listsExpanded = sources.expanded.lists,
        tagsExpanded = sources.expanded.tags,
        bottomBarSlots = slots,
        inboxProjectId = inboxId ?: 0L,
        reviewEnabled = reviewPrefs.enabled,
        reviewOverdueCount = reviewOverdue,
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
