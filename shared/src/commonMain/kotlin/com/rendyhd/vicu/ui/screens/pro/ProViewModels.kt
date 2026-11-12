package com.rendyhd.vicu.ui.screens.pro

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.data.repository.ProActions
import com.rendyhd.vicu.data.repository.ProWorld
import com.rendyhd.vicu.data.repository.ProWorldSource
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.ui.navigation.NavigationTicker
import com.rendyhd.vicu.ui.screens.shared.CompletionHold
import com.rendyhd.vicu.ui.screens.shared.CompletionToast
import com.rendyhd.vicu.ui.screens.shared.NoCompletionToast
import com.rendyhd.vicu.ui.screens.shared.TaskProjectGroup
import com.rendyhd.vicu.ui.screens.shared.buildTaskProjectGroups
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.DateDisplayFormat
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.PlatformFiles
import com.rendyhd.vicu.util.pro.Agenda
import com.rendyhd.vicu.util.pro.AvailabilityView
import com.rendyhd.vicu.util.pro.People
import com.rendyhd.vicu.util.pro.PersonSections
import com.rendyhd.vicu.util.pro.ProListKeys
import com.rendyhd.vicu.util.pro.ProScreens
import com.rendyhd.vicu.util.pro.Promises
import com.rendyhd.vicu.util.pro.Recap
import com.rendyhd.vicu.util.pro.RecapScope
import com.rendyhd.vicu.util.pro.Recaps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus

/** What the Pro list screens share: the world, and completing a row with the completion hold. */
abstract class ProTaskListViewModel(
    protected val worldSource: ProWorldSource,
    private val taskRepository: TaskRepository,
    navigationTicker: NavigationTicker,
    completionToast: CompletionToast,
) : ViewModel() {
    /** Rows completed on this screen, kept in place for a moment (see [CompletionHold]). */
    val completions = CompletionHold(viewModelScope, navigationTicker = navigationTicker, toast = completionToast)

    val completedIds: StateFlow<Set<Long>> = completions.heldIds.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun toggleDone(task: Task) {
        viewModelScope.launch {
            if (!task.done) completions.hold(task)
            val result = taskRepository.toggleDone(task)
            if (result is NetworkResult.Error) {
                completions.release(task.id)
                _error.value = result.message
            }
        }
    }

    fun undoComplete(task: Task) {
        viewModelScope.launch {
            completions.undoing(task.id)
            val result = taskRepository.setDone(task.id, false)
            completions.release(task.id)
            if (result is NetworkResult.Error) _error.value = result.message
        }
    }

    fun clearError() {
        _error.value = null
    }
}

data class FlaggedUiState(
    val on: Boolean = false,
    val groups: List<TaskProjectGroup> = emptyList(),
    val view: AvailabilityView = AvailabilityView.AVAILABLE,
    val hidden: Int = 0,
)

/** Flagged (contract 21.4): open flagged tasks by project, with Available / Remaining / All. */
class FlaggedViewModel(
    worldSource: ProWorldSource,
    taskRepository: TaskRepository,
    private val actions: ProActions,
    navigationTicker: NavigationTicker = NavigationTicker(),
    completionToast: CompletionToast = NoCompletionToast,
) : ProTaskListViewModel(worldSource, taskRepository, navigationTicker, completionToast) {

    private fun build(world: ProWorld): FlaggedUiState {
        if (!world.availabilityOn) return FlaggedUiState(on = false)
        val flagged = ProScreens.flagged(world.open, world.projects.mapTo(HashSet()) { it.id })
        val view = world.listView(ProListKeys.FLAGGED) ?: AvailabilityView.AVAILABLE
        val filtered = ProScreens.filter(flagged, view, world::availabilityOf)
        val shown = completions.merge(filtered.tasks)
        return FlaggedUiState(true, buildTaskProjectGroups(shown, world.projects, inboxId = null), view, filtered.hidden)
    }

    val uiState: StateFlow<FlaggedUiState> = combine(worldSource.world, completions.state) { world, _ -> build(world) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, build(worldSource.world.value))

    fun setView(view: AvailabilityView) = actions.setListView(ProListKeys.FLAGGED, view.toListView())
}

/** One person's group on Waiting on. */
data class WaitingGroupUi(val person: Long?, val name: String, val hexColor: String, val tasks: List<Task>)

data class WaitingOnUiState(val on: Boolean = false, val groups: List<WaitingGroupUi> = emptyList())

/**
 * Waiting on (contract 21.6): open promises of theirs and tasks with an on-hold label, grouped by
 * person, the earliest due first. A promise outside the area does not count as one.
 */
class WaitingOnViewModel(
    worldSource: ProWorldSource,
    taskRepository: TaskRepository,
    navigationTicker: NavigationTicker = NavigationTicker(),
    completionToast: CompletionToast = NoCompletionToast,
) : ProTaskListViewModel(worldSource, taskRepository, navigationTicker, completionToast) {

    private fun build(world: ProWorld): WaitingOnUiState {
        if (!world.promisesOn) return WaitingOnUiState(on = false)
        val tasks = world.open.filter { Promises.promiseOf(world.ledger, it.id) == null || world.promiseFor(it) != null }
        val groups = Promises.waitingOn(tasks, world.ledger, world::labelOnHold, world::isPerson, world.zone).map { group ->
            val label = world.label(group.person)
            WaitingGroupUi(
                person = group.person,
                name = if (group.person == null) "No one" else label?.title ?: "Someone",
                hexColor = label?.hexColor.orEmpty(),
                tasks = completions.merge(group.tasks.mapNotNull { world.openById[it] }, listScope = group.person ?: -1L),
            )
        }
        return WaitingOnUiState(on = true, groups = groups)
    }

    val uiState: StateFlow<WaitingOnUiState> = combine(worldSource.world, completions.state) { world, _ -> build(world) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, build(worldSource.world.value))
}

data class PersonUiState(
    val on: Boolean = false,
    val label: Label? = null,
    val isPerson: Boolean = false,
    /** The last 90 days in plain words (decision 9). */
    val pattern: String = "",
    val sections: PersonSections = PersonSections(),
    val agenda: Agenda = Agenda(emptyList(), ""),
)

/**
 * A person's page (contract 21.5, plan 9.2): what they promised you, what you owe them, what waits
 * to be confirmed, the last 90 days in plain words, the next 1:1's agenda and a recap, both sent
 * through the share sheet.
 */
class PersonViewModel(
    savedStateHandle: SavedStateHandle,
    worldSource: ProWorldSource,
    taskRepository: TaskRepository,
    private val actions: ProActions,
    private val platformFiles: PlatformFiles,
    private val appMessages: AppMessages,
    navigationTicker: NavigationTicker = NavigationTicker(),
    completionToast: CompletionToast = NoCompletionToast,
) : ProTaskListViewModel(worldSource, taskRepository, navigationTicker, completionToast) {

    private val labelId: Long = savedStateHandle["labelId"]!!

    private fun build(world: ProWorld): PersonUiState {
        if (!world.promisesOn) return PersonUiState(on = false)
        val label = world.label(labelId)
        val name = label?.title ?: "Person"
        val sections = ProScreens.personSections(labelId, world.open, world::promiseFor, world.peopleWithAliases)
        val counts = People.personCounts(labelId, world.ledger, { it in world.openById }, world.today, world.zone)
        return PersonUiState(
            on = true,
            label = label,
            isPerson = world.isPerson(labelId),
            pattern = People.patternText(counts),
            sections = PersonSections(
                theirs = completions.merge(sections.theirs, listScope = THEIRS),
                mine = completions.merge(sections.mine, listScope = MINE),
                confirm = completions.merge(sections.confirm, listScope = CONFIRM),
            ),
            agenda = ProScreens.agenda(name, labelId, sections.theirs, sections.mine, world::promiseFor, world.ledger, world.today, world.zone),
        )
    }

    val uiState: StateFlow<PersonUiState> = combine(worldSource.world, completions.state) { world, _ -> build(world) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, build(worldSource.world.value))

    /** The label becomes a person (from a page opened for a label that is not one yet). */
    fun markAsPerson() = actions.setPerson(labelId, true)

    /** The 1:1 agenda through the share sheet. */
    fun shareAgenda() {
        val state = uiState.value
        platformFiles.shareText(state.agenda.text, subject = "1:1 with ${state.label?.title ?: "Person"}")?.let(appMessages::post)
    }

    /**
     * The recap of the last [days] local days (today included) for this person (contract 21.8):
     * delivered, agreed, changed and open, with what you owe when [includeMine].
     */
    fun recap(days: Int, includeMine: Boolean, includeQuotes: Boolean, fmt: DateDisplayFormat): Recap {
        val world = worldSource.world.value
        val name = world.label(labelId)?.title ?: "Person"
        val scope = RecapScope(
            from = world.today.minus((days - 1).coerceAtLeast(0), DateTimeUnit.DAY),
            to = world.today,
            people = listOf(labelId),
            includeMine = includeMine,
        )
        val tasks = (world.open + world.donePromiseTasks).filter { world.promiseFor(it) != null }
        val items = Recaps.items(scope, tasks, world.ledger, world.zone)
        return Recaps.build(
            title = "Recap for $name",
            date = world.today,
            items = items,
            personName = world::nameOf,
            notDiscussed = emptyList(),
            absent = emptyList(),
            includeQuotes = includeQuotes,
            now = world.now,
            zone = world.zone,
            fmt = fmt,
        )
    }

    /** Shares the recap; sharing marks its promises as sent (decision 7). */
    fun shareRecap(recap: Recap) {
        val problem = platformFiles.shareText(recap.text, subject = recap.text.lineSequence().firstOrNull())
        if (problem != null) {
            appMessages.post(problem)
            return
        }
        viewModelScope.launch {
            actions.markSent(recap.taskIds)
            if (recap.taskIds.isNotEmpty()) {
                val count = recap.taskIds.size
                appMessages.post(if (count == 1) "1 promise marked as sent" else "$count promises marked as sent")
            }
        }
    }

    private companion object {
        const val THEIRS = -1L
        const val MINE = -2L
        const val CONFIRM = -3L
    }
}

/** The device setting's value for a view. */
internal fun AvailabilityView.toListView(): com.rendyhd.vicu.data.local.ProListView = when (this) {
    AvailabilityView.AVAILABLE -> com.rendyhd.vicu.data.local.ProListView.AVAILABLE
    AvailabilityView.REMAINING -> com.rendyhd.vicu.data.local.ProListView.REMAINING
    AvailabilityView.ALL -> com.rendyhd.vicu.data.local.ProListView.ALL
}
