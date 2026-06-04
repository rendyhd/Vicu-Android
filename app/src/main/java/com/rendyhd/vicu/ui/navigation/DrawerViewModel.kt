package com.rendyhd.vicu.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.local.BottomBarPrefsStore
import com.rendyhd.vicu.data.local.CustomListStore
import com.rendyhd.vicu.data.local.LabelOrderPrefsStore
import com.rendyhd.vicu.data.local.ReviewPrefsStore
import com.rendyhd.vicu.domain.model.BottomBarSlot
import com.rendyhd.vicu.domain.model.BottomBarSlotType
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.util.ReviewMetadata
import com.rendyhd.vicu.util.ReviewState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

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

@HiltViewModel
class DrawerViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    labelRepository: LabelRepository,
    private val customListStore: CustomListStore,
    private val authManager: AuthManager,
    private val bottomBarPrefsStore: BottomBarPrefsStore,
    private val reviewPrefsStore: ReviewPrefsStore,
    private val labelOrderPrefsStore: LabelOrderPrefsStore,
    behaviorPrefsStore: com.rendyhd.vicu.data.local.BehaviorPrefsStore,
) : ViewModel() {

    /** Exposed for the app-root CompositionLocal that positions the FAB. */
    val fabAlignStart: StateFlow<Boolean> = behaviorPrefsStore.getPrefs()
        .map { it.fabAlignStart }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _sectionsExpanded = MutableStateFlow(
        Triple(true, true, true), // projects, lists, tags
    )

    private val _inboxProjectId = MutableStateFlow<Long?>(null)

    init {
        viewModelScope.launch {
            _inboxProjectId.value = authManager.getInboxProjectId()
        }
    }

    val uiState: StateFlow<DrawerUiState> = combine(
        combine(
            projectRepository.getAll(),
            labelRepository.getAll(),
            customListStore.getAll(),
            _sectionsExpanded,
            _inboxProjectId,
        ) { projects, labels, customLists, expanded, inboxId ->
            listOf(projects, labels, customLists, expanded, inboxId)
        },
        bottomBarPrefsStore.slots,
        reviewPrefsStore.getPrefs(),
        labelOrderPrefsStore.getOrder(),
    ) { base, slots, reviewPrefs, labelOrder ->
        @Suppress("UNCHECKED_CAST")
        val projects = base[0] as List<Project>
        val labels = base[1] as List<Label>
        val customLists = base[2] as List<CustomList>
        val expanded = base[3] as Triple<Boolean, Boolean, Boolean>
        val inboxId = base[4] as Long?

        val reviewOverdue = projects
            .asSequence()
            .filterNot { it.isArchived }
            .filterNot { reviewPrefs.excludeInbox && inboxId != null && it.id == inboxId }
            .map { ReviewMetadata.computeStatus(ReviewMetadata.parse(it.description), reviewPrefs.defaultCadenceDays) }
            .filter { it.metadata.state != ReviewState.EXCLUDED }
            .count { it.isOverdue }

        val nonArchived = projects.filter { !it.isArchived && it.id != inboxId }
        val roots = nonArchived
            .filter { it.parentProjectId == 0L }
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

        DrawerUiState(
            projectTree = tree,
            allProjects = nonArchived,
            labels = applyLabelOrder(labels, labelOrder),
            customLists = customLists,
            projectsExpanded = expanded.first,
            listsExpanded = expanded.second,
            tagsExpanded = expanded.third,
            bottomBarSlots = slots,
            inboxProjectId = inboxId ?: 0L,
            reviewEnabled = reviewPrefs.enabled,
            reviewOverdueCount = reviewOverdue,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DrawerUiState())

    fun toggleProjectsExpanded() {
        _sectionsExpanded.value = _sectionsExpanded.value.copy(
            first = !_sectionsExpanded.value.first,
        )
    }

    fun toggleListsExpanded() {
        _sectionsExpanded.value = _sectionsExpanded.value.copy(
            second = !_sectionsExpanded.value.second,
        )
    }

    fun toggleTagsExpanded() {
        _sectionsExpanded.value = _sectionsExpanded.value.copy(
            third = !_sectionsExpanded.value.third,
        )
    }

    fun saveCustomList(customList: CustomList) {
        viewModelScope.launch {
            customListStore.save(customList)
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
            customListStore.reorder(fromIndex, toIndex)
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

    /** Orders [labels] by the stored client-side [order]; ids not present fall back to A→Z at the end. */
    private fun applyLabelOrder(labels: List<Label>, order: List<Long>): List<Label> {
        if (order.isEmpty()) return labels.sortedBy { it.title.lowercase() }
        val byId = labels.associateBy { it.id }
        val ordered = order.mapNotNull { byId[it] }
        val remaining = labels.filterNot { it.id in order }.sortedBy { it.title.lowercase() }
        return ordered + remaining
    }
}
