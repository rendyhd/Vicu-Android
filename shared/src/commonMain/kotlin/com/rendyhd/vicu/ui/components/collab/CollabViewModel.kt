package com.rendyhd.vicu.ui.components.collab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.domain.model.CollabState
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskSignal
import com.rendyhd.vicu.domain.model.User
import com.rendyhd.vicu.domain.repository.CollaborationRepository
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.postIfRefused
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Collaboration for the whole app (one per activity, held by [com.rendyhd.vicu.ui.VicuApp]): the
 * access of every project and the comment signals, as [LocalCollab] hands them to the rows and the
 * editor, and the assignee changes they start.
 */
class CollabViewModel(
    private val collaboration: CollaborationRepository,
    private val appMessages: AppMessages,
) : ViewModel() {

    val state: StateFlow<CollabState> =
        collaboration.state.stateIn(viewModelScope, SharingStarted.Eagerly, CollabState())

    val signals: StateFlow<Map<Long, TaskSignal>> =
        collaboration.signals.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        // The screens' refresh reads members and signals too, but skips everything while the app
        // is fresh; the members are kept for ten minutes, so this costs nothing when they are known.
        viewModelScope.launch { collaboration.refresh() }
    }

    fun setAssigned(task: Task, person: User, assigned: Boolean) {
        viewModelScope.launch {
            val who = person.name.trim().ifEmpty { person.username }
            appMessages.postIfRefused(
                collaboration.setAssigned(task, person, assigned),
                if (assigned) "assign $who" else "unassign $who",
            )
        }
    }

    suspend fun memberPermissions(projectId: Long): Map<Long, Int?> = collaboration.memberPermissions(projectId)
}
