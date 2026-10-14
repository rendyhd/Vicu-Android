package com.rendyhd.vicu.ui.screens.routines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.data.local.RoutinePrefsStore
import com.rendyhd.vicu.domain.model.OccurrenceStatus
import com.rendyhd.vicu.domain.model.Routine
import com.rendyhd.vicu.domain.model.RoutineDay
import com.rendyhd.vicu.domain.model.RoutineDraft
import com.rendyhd.vicu.domain.model.RoutineOccurrence
import com.rendyhd.vicu.domain.model.RoutineOccurrenceRecord
import com.rendyhd.vicu.domain.repository.RoutineCsvExport
import com.rendyhd.vicu.domain.repository.RoutineParseIssue
import com.rendyhd.vicu.domain.repository.RoutineRepository
import com.rendyhd.vicu.domain.repository.PlatformRepositoryHooks
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.NetworkResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RoutinesUiState(
    val day: RoutineDay = RoutineDay("", emptyList()),
    val active: List<Routine> = emptyList(),
    val archived: List<Routine> = emptyList(),
    val issues: List<RoutineParseIssue> = emptyList(),
    /** A problem archiving old history that did not stop a change from being saved. */
    val archiveWarning: String? = null,
    val isSaving: Boolean = false,
    val error: String? = null,
)

class RoutinesViewModel(
    private val repository: RoutineRepository,
    private val prefsStore: RoutinePrefsStore,
    private val platformHooks: PlatformRepositoryHooks,
    private val dayClock: DayClock,
) : ViewModel() {
    private val operationState = MutableStateFlow(false to null as String?)

    init {
        viewModelScope.launch {
            // Once at start and again whenever the day changes.
            dayClock.today.collect { repository.finalizeAndPrune() }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val dayFlow = dayClock.today.flatMapLatest { date -> repository.observeDay(date.toString()) }

    val uiState: StateFlow<RoutinesUiState> = combine(
        combine(
            dayFlow,
            repository.observeActive(),
            repository.observeArchived(),
            repository.observeIssues(),
            operationState,
        ) { day, active, archived, issues, operation ->
            RoutinesUiState(
                day = day,
                active = active,
                archived = archived,
                issues = issues,
                isSaving = operation.first,
                error = operation.second,
            )
        },
        repository.archiveWarning,
    ) { state, archiveWarning -> state.copy(archiveWarning = archiveWarning) }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        RoutinesUiState(day = RoutineDay(dayClock.day.value.date.toString(), emptyList())),
    )

    val remindersEnabled: StateFlow<Boolean> = prefsStore.remindersEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Routines turned off in Settings; the screen is still reachable from a widget or a notification. */
    val routinesEnabled: StateFlow<Boolean> = prefsStore.enabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun save(draft: RoutineDraft, routineId: String? = null, onSaved: () -> Unit = {}) {
        launchOperation(onSuccess = onSaved) {
            if (routineId == null) repository.create(draft) else repository.update(routineId, draft)
        }
    }

    fun toggle(occurrence: RoutineOccurrence) {
        val next = if (occurrence.status == OccurrenceStatus.COMPLETED) {
            OccurrenceStatus.PENDING
        } else {
            OccurrenceStatus.COMPLETED
        }
        setStatus(occurrence, next)
    }

    fun skip(occurrence: RoutineOccurrence) = setStatus(occurrence, OccurrenceStatus.SKIPPED)

    fun archive(routine: Routine, archived: Boolean) {
        launchOperation { repository.archive(routine.definition.id, archived) }
    }

    fun deletePermanently(routine: Routine) {
        launchOperation { repository.deletePermanently(routine.definition.id) }
    }

    fun clearError() {
        operationState.update { it.first to null }
    }

    fun observeHistory(routineId: String): Flow<List<RoutineOccurrenceRecord>> =
        repository.observeHistory(routineId)

    fun setRemindersEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefsStore.setRemindersEnabled(enabled)
            platformHooks.routinesChanged()
        }
    }

    fun exportCsv(onReady: (RoutineCsvExport) -> Unit) {
        viewModelScope.launch { onReady(repository.exportCsvWithStatus()) }
    }

    private fun setStatus(occurrence: RoutineOccurrence, status: OccurrenceStatus) {
        launchOperation {
            repository.setOccurrenceStatus(
                routineId = occurrence.routine.definition.id,
                date = occurrence.scheduledDate,
                slotId = occurrence.slot.id,
                status = status,
            )
        }
    }

    private fun launchOperation(
        onSuccess: () -> Unit = {},
        block: suspend () -> NetworkResult<*>,
    ) {
        viewModelScope.launch {
            operationState.value = true to null
            when (val result = block()) {
                is NetworkResult.Success -> {
                    operationState.value = false to null
                    onSuccess()
                }
                is NetworkResult.Error -> operationState.value = false to result.message
                NetworkResult.Loading -> operationState.value = false to null
            }
        }
    }
}
