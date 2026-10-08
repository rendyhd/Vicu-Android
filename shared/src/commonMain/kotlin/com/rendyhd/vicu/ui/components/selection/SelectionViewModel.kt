package com.rendyhd.vicu.ui.components.selection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.ui.navigation.NavigationTicker
import com.rendyhd.vicu.ui.screens.shared.CompletionHold
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.descendantsDepthFirst
import com.rendyhd.vicu.util.RelationKind
import com.rendyhd.vicu.util.unfinishedDescendants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Per-screen multi-select state + bulk actions. Scoped to the screen's NavBackStackEntry via
 * koinViewModel(), so selection is contextual to one list: it survives a rotation, and it ends
 * when the app navigates to another destination ([NavigationTicker]), including a tab that is
 * saved and restored with its view model.
 *
 * Bulk ops send the COMPLETE Task object (Go zero-value problem) or use the dedicated
 * move/label endpoints; the list ViewModels observe Room flows and update automatically.
 *
 * Every bulk action runs the tasks a few at a time, reports the ones that failed in the app-wide
 * snackbar and leaves just those selected so the user can try again. The work runs in
 * [appScope], so leaving the screen does not stop a half-finished action or its Undo.
 */
class SelectionViewModel(
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val labelRepository: LabelRepository,
    private val appMessages: AppMessages,
    private val appScope: CoroutineScope,
    private val dayClock: DayClock,
    navigationTicker: NavigationTicker = NavigationTicker(),
) : ViewModel() {

    init {
        // Read now, not when the collector starts, so a navigation in between is not missed.
        var seen = navigationTicker.count.value
        viewModelScope.launch {
            navigationTicker.count.collect { count ->
                if (count != seen) {
                    seen = count
                    clear()
                }
            }
        }
    }

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()
    private val _selectedDescendantCount = MutableStateFlow(0)
    val selectedDescendantCount: StateFlow<Int> = _selectedDescendantCount.asStateFlow()
    private val _pendingCompletionDescendantCount = MutableStateFlow<Int?>(null)
    val pendingCompletionDescendantCount: StateFlow<Int?> = _pendingCompletionDescendantCount.asStateFlow()
    private var pendingCompletionTasks: List<Task> = emptyList()
    private var pendingCompletionHold: CompletionHold? = null

    val projects: StateFlow<List<Project>> = projectRepository.getAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val labels: StateFlow<List<Label>> = labelRepository.getAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun toggle(id: Long) {
        _selectedIds.update { if (id in it) it - id else it + id }
        refreshSelectedDescendantCount()
    }

    fun clear() {
        _selectedIds.value = emptySet()
        _selectedDescendantCount.value = 0
        _pendingCompletionDescendantCount.value = null
        pendingCompletionTasks = emptyList()
        pendingCompletionHold = null
    }

    /**
     * Completes the selected tasks the way a tap on each checkbox does. [completions] is the
     * screen's hold: the completed rows stay in the list, struck through, for a moment.
     */
    fun bulkComplete(completions: CompletionHold? = null) {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        appScope.launch {
            val tasks = rootSelection(taskRepository.getByIds(ids).filter { !it.done }, ids)
            val descendantCount = tasks
                .flatMap { it.unfinishedDescendants() }
                .distinctBy { it.id }
                .size
            if (descendantCount > 0) {
                pendingCompletionTasks = tasks
                pendingCompletionHold = completions
                _pendingCompletionDescendantCount.value = descendantCount
            } else {
                completeTasks(tasks, completions)
            }
        }
    }

    fun confirmBulkComplete() {
        val tasks = pendingCompletionTasks
        val completions = pendingCompletionHold
        if (tasks.isEmpty()) return
        appScope.launch { completeTasks(tasks, completions) }
    }

    fun dismissBulkComplete() {
        pendingCompletionTasks = emptyList()
        pendingCompletionHold = null
        _pendingCompletionDescendantCount.value = null
    }

    private suspend fun completeTasks(tasks: List<Task>, completions: CompletionHold?) {
        // Hold every row before the first request, so none of them vanishes while it is sent.
        val heldIds = tasks.filter { completions?.hold(it) == true }.mapTo(HashSet()) { it.id }
        val byId = tasks.associateBy { it.id }
        val outcomes = runBulk(tasks.map { it.id }) { taskRepository.toggleDone(byId.getValue(it)) }
        outcomes.filter { it.failed }.forEach { completions?.release(it.id) }
        // A held row joins the completion toast when its hold ends ("3 completed", Undo); only
        // rows that were not held on a screen (no list to keep them in) are announced here.
        val completedIds = outcomes.filter { !it.failed && it.id !in heldIds }.map { it.id }
        finishBulk("complete", outcomes)
        if (completedIds.isNotEmpty()) {
            val count = completedIds.size
            appMessages.post(
                message = if (count == 1) "Task completed" else "$count tasks completed",
                actionLabel = "Undo",
                onAction = { undoBulkComplete(completedIds, completions) },
            )
        }
    }

    /** One undo for the whole batch: reopens exactly the tasks that were completed. */
    private fun undoBulkComplete(taskIds: List<Long>, completions: CompletionHold?) {
        appScope.launch {
            taskIds.forEach { completions?.undoing(it) }
            val outcomes = runBulk(taskIds) { taskRepository.setDone(it, false) }
            taskIds.forEach { completions?.release(it) }
            reportFailures("reopen", outcomes)
        }
    }

    private fun rootSelection(
        tasks: List<Task>,
        selectedIds: Set<Long>,
    ): List<Task> = tasks.filter { task ->
        task.relatedTasks[RelationKind.PARENTTASK]
            .orEmpty()
            .none { it.id in selectedIds }
    }

    private var descendantCountJob: Job? = null

    /**
     * Recounts the nested subtasks of the selection. Only the latest request may write the count:
     * a slow answer for an earlier selection used to overwrite the one for the current selection.
     */
    private fun refreshSelectedDescendantCount() {
        val ids = _selectedIds.value
        descendantCountJob?.cancel()
        descendantCountJob = viewModelScope.launch {
            _selectedDescendantCount.value = taskRepository.getByIds(ids)
                .flatMap { it.descendantsDepthFirst() }
                .distinctBy { it.id }
                .size
        }
    }

    fun bulkMove(projectId: Long) {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        appScope.launch {
            val outcomes = runBulk(ids.toList()) { taskRepository.moveToProject(it, projectId) }
            finishBulk("move", outcomes)
        }
    }

    fun bulkToday() {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        appScope.launch {
            val day = dayClock.day.value
            val dueDate = DueDates.today(day.date, day.zone).toString()
            updateSelected(ids, "schedule") { it.copy(dueDate = dueDate) }
        }
    }

    fun bulkSchedule(dueDate: String) {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        appScope.launch { updateSelected(ids, "schedule") { it.copy(dueDate = dueDate) } }
    }

    fun bulkSetPriority(priority: Int) {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        appScope.launch { updateSelected(ids, "change the priority of") { it.copy(priority = priority) } }
    }

    fun bulkRemove() {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        appScope.launch {
            if (taskRepository.getByIds(ids).any { it.descendantsDepthFirst().isNotEmpty() }) {
                refreshSelectedDescendantCount()
                return@launch
            }
            val outcomes = runBulk(ids.toList()) { taskRepository.delete(it) }
            finishBulk("remove", outcomes)
        }
    }

    fun bulkApplyLabel(labelId: Long) {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        appScope.launch { applyLabel(ids, labelId) }
    }

    /**
     * The label picker's "Create new label": creates the label, then puts it on every selected
     * task like [bulkApplyLabel]. A label that cannot be created is reported and the selection
     * stays as it was.
     */
    fun createLabelAndApply(name: String, hexColor: String) {
        val ids = _selectedIds.value
        if (ids.isEmpty() || name.isBlank()) return
        appScope.launch {
            when (val created = labelRepository.create(Label(id = 0, title = name.trim(), hexColor = hexColor))) {
                is NetworkResult.Success -> applyLabel(ids, created.data.id)
                is NetworkResult.Error -> appMessages.post("Could not create the label: ${created.message}")
                is NetworkResult.Loading -> Unit
            }
        }
    }

    private suspend fun applyLabel(ids: Set<Long>, labelId: Long) {
        val outcomes = runBulk(ids.toList()) { labelRepository.addToTask(it, labelId) }
        finishBulk("label", outcomes)
    }

    private suspend fun updateSelected(ids: Set<Long>, verb: String, change: (Task) -> Task) {
        val byId = taskRepository.getByIds(ids).associateBy { it.id }
        // A selected task that is gone from the cache counts as a failure, not as done.
        val missing = ids.filter { it !in byId }.map { BulkOutcome(it, NetworkResult.Error("Task is no longer available")) }
        val outcomes = runBulk(byId.keys.toList()) { taskRepository.update(change(byId.getValue(it))) }
        finishBulk(verb, outcomes + missing)
    }

    private class BulkOutcome(val id: Long, val result: NetworkResult<*>) {
        val failed: Boolean get() = result is NetworkResult.Error
    }

    /**
     * Runs [action] for every id, [BULK_PARALLELISM] at a time. The actions only touch their own
     * task (the one shared row, a parent's list of subtasks, is updated under a lock in the
     * repository), so they can overlap. An action that throws counts as a failure.
     */
    private suspend fun runBulk(
        ids: List<Long>,
        action: suspend (Long) -> NetworkResult<*>,
    ): List<BulkOutcome> {
        val permits = Semaphore(BULK_PARALLELISM)
        return coroutineScope {
            ids.map { id ->
                async {
                    permits.withPermit {
                        val result = try {
                            action(id)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            NetworkResult.Error(e.message ?: "Unexpected error")
                        }
                        BulkOutcome(id, result)
                    }
                }
            }.awaitAll()
        }
    }

    /** Reports what failed and leaves exactly those tasks selected; clears the selection when none did. */
    private fun finishBulk(verb: String, outcomes: List<BulkOutcome>) {
        reportFailures(verb, outcomes)
        val failedIds = outcomes.filter { it.failed }.mapTo(LinkedHashSet()) { it.id }
        if (failedIds.isEmpty()) {
            clear()
        } else {
            _selectedIds.value = failedIds
            pendingCompletionTasks = emptyList()
            pendingCompletionHold = null
            _pendingCompletionDescendantCount.value = null
            refreshSelectedDescendantCount()
        }
    }

    private fun reportFailures(verb: String, outcomes: List<BulkOutcome>) {
        val failures = outcomes.filter { it.failed }
        if (failures.isEmpty()) return
        val reason = (failures.first().result as NetworkResult.Error).message
        val noun = if (outcomes.size == 1) "task" else "tasks"
        appMessages.post("Could not $verb ${failures.size} of ${outcomes.size} $noun: $reason")
    }

    private companion object {
        /** How many requests a bulk action keeps in flight at once. */
        const val BULK_PARALLELISM = 4
    }
}
