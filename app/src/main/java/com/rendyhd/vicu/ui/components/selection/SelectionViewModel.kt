package com.rendyhd.vicu.ui.components.selection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Per-screen multi-select state + bulk actions. Scoped to the screen's NavBackStackEntry via
 * hiltViewModel(), so selection is contextual to one list and clears when you leave.
 *
 * Bulk ops send the COMPLETE Task object (Go zero-value problem) or use the dedicated
 * move/label endpoints; the list ViewModels observe Room flows and update automatically.
 */
@HiltViewModel
class SelectionViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val labelRepository: LabelRepository,
) : ViewModel() {

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    val projects: StateFlow<List<Project>> = projectRepository.getAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val labels: StateFlow<List<Label>> = labelRepository.getAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun toggle(id: Long) {
        _selectedIds.update { if (id in it) it - id else it + id }
    }

    fun clear() {
        _selectedIds.value = emptySet()
    }

    fun bulkComplete() {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            taskRepository.getByIds(ids).filter { !it.done }.forEach { task ->
                taskRepository.update(task.copy(done = true, doneAt = DateUtils.nowIso()))
            }
            clear()
        }
    }

    fun bulkMove(projectId: Long) {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { taskRepository.moveToProject(it, projectId) }
            clear()
        }
    }

    fun bulkSchedule() {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            taskRepository.getByIds(ids).forEach { taskRepository.applyScheduleAction(it) }
            clear()
        }
    }

    fun bulkApplyLabel(labelId: Long) {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { labelRepository.addToTask(it, labelId) }
            clear()
        }
    }
}
