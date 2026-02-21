package com.rendyhd.vicu.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.local.CustomListStore
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
)

@HiltViewModel
class DrawerViewModel @Inject constructor(
    projectRepository: ProjectRepository,
    labelRepository: LabelRepository,
    private val customListStore: CustomListStore,
    private val authManager: AuthManager,
) : ViewModel() {

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
        projectRepository.getAll(),
        labelRepository.getAll(),
        customListStore.getAll(),
        _sectionsExpanded,
        _inboxProjectId,
    ) { projects, labels, customLists, expanded, inboxId ->
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
            labels = labels.sortedBy { it.title.lowercase() },
            customLists = customLists,
            projectsExpanded = expanded.first,
            listsExpanded = expanded.second,
            tagsExpanded = expanded.third,
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
}
