package com.rendyhd.vicu.ui.screens.shared

import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.navigation.NavigationTicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A row kept on screen after its stored task changed: the task as shown, and where it was.
 * [changed] is false while the user's undo of the change is being stored, so the row is drawn as
 * it was before but stays in place until the stored task has caught up.
 */
data class HeldRow(val task: Task, val scope: Long, val index: Int, val changed: Boolean = true)

/**
 * Keeps a row where it is for a few seconds after it was completed (or reopened) on this
 * screen. Completing a task changes the stored task at once, so a list that shows open tasks
 * would drop the row immediately; this holds a copy so the checkbox feels the same as it always
 * did: the row stays, struck through, and a second tap undoes it. The hold ends after
 * [holdMillis] (the contract's 5 s), when the user undoes the change or goes to another
 * destination (the [navigationTicker]; a rotation does not end it), or when the change fails
 * ([release]). A row whose hold ends by time or navigation collapses: it leaves the list and is
 * handed to [toast], which shows "Completed, Undo" (docs/cross-app-semantics-v1.md section 7).
 *
 * Use one instance per screen. A screen passes each list it shows through [merge]; lists that
 * are shown several times (a project and its sub-project sections) give each its own [merge]
 * `listScope`. Everything runs on the main thread.
 */
class CompletionHold(
    private val scope: CoroutineScope,
    private val holdMillis: Long = DEFAULT_HOLD_MILLIS,
    navigationTicker: NavigationTicker? = null,
    private val toast: CompletionToast = NoCompletionToast,
) {
    private val _state = MutableStateFlow<Map<Long, HeldRow>>(emptyMap())

    init {
        // The screen's view model outlives its composition, so a rotation leaves the hold alone;
        // going to another destination ends it.
        if (navigationTicker != null) {
            var seen = navigationTicker.count.value
            scope.launch {
                navigationTicker.count.collect { count ->
                    if (count != seen) {
                        seen = count
                        releaseAll()
                    }
                }
            }
        }
    }

    /** The held rows; collect it next to the screen's task flows so a change re-merges them. */
    val state: StateFlow<Map<Long, HeldRow>> = _state.asStateFlow()

    /** Ids of the rows to draw as just changed (struck through when completing). */
    val heldIds: Flow<Set<Long>>
        get() = _state.map { rows -> rows.filterValues { it.changed }.keys }.distinctUntilChanged()

    /** The rows held right now as completed (not those being reopened): they no longer count as open. */
    val completedIds: Set<Long>
        get() = _state.value.filterValues { it.changed }.keys

    private val shown = HashMap<Long, List<Long>>()
    private val timers = HashMap<Long, Job>()

    /**
     * Holds [task] (as it is shown now) in the place it has in the list last passed to [merge].
     * Returns false, and holds nothing, when it is not a row of this screen (a subtask completed
     * from inside its parent's row, for example).
     */
    fun hold(task: Task): Boolean {
        val (listScope, index) = locate(task.id) ?: return false
        _state.update { it + (task.id to HeldRow(task, listScope, index)) }
        timers.remove(task.id)?.cancel()
        timers[task.id] = scope.launch {
            delay(holdMillis)
            collapse(task.id)
        }
        return true
    }

    /**
     * The user undid the change: draw the row as it was but keep it in place until the undo is
     * stored, then [release] it. Without this the row would vanish for the length of the request.
     */
    fun undoing(taskId: Long) {
        _state.update { rows ->
            val row = rows[taskId] ?: return@update rows
            rows + (taskId to row.copy(changed = false))
        }
    }

    fun release(taskId: Long) {
        timers.remove(taskId)?.cancel()
        _state.update { it - taskId }
    }

    /** The user left the screen: nothing is held any longer, and the completed rows collapse. */
    fun releaseAll() {
        val collapsing = _state.value.filterValues { it.changed }.keys.sorted()
        timers.values.forEach { it.cancel() }
        timers.clear()
        _state.value = emptyMap()
        collapsing.forEach(toast::collapsed)
    }

    /** The hold of a completed row ended: it leaves the list and joins the toast. */
    private fun collapse(taskId: Long) {
        val completed = _state.value[taskId]?.changed == true
        release(taskId)
        if (completed) toast.collapsed(taskId)
    }

    /**
     * [source] plus the held rows of [listScope] that are no longer in it, each back at the
     * position it had. A held row that is still in [source] is left as it is.
     */
    fun merge(source: List<Task>, listScope: Long = 0L): List<Task> {
        val held = _state.value.values
            .filter { it.scope == listScope }
            .sortedBy { it.index }
        val result = if (held.isEmpty()) {
            source
        } else {
            val present = source.mapTo(HashSet()) { it.id }
            val merged = source.toMutableList()
            held.forEach { row ->
                if (row.task.id !in present) merged.add(row.index.coerceIn(0, merged.size), row.task)
            }
            merged
        }
        shown[listScope] = result.map { it.id }
        return result
    }

    private fun locate(taskId: Long): Pair<Long, Int>? {
        for ((listScope, ids) in shown) {
            val index = ids.indexOf(taskId)
            if (index >= 0) return listScope to index
        }
        return null
    }

    companion object {
        /** How long a completed row stays in its list (the contract's `completion.holdMs`). */
        const val HOLD_MILLIS = 5_000L

        /** How long the "Completed, Undo" toast stays (`completion.toastMs`). */
        const val TOAST_MILLIS = 6_000L

        /** The toast's action label (`completion.toast.action`). */
        const val TOAST_ACTION = "Undo"

        /** "Completed" for one task, "{n} completed" for two or more (`completion.toast`). */
        fun toastText(count: Int): String = if (count <= 1) "Completed" else "$count completed"

        const val DEFAULT_HOLD_MILLIS = HOLD_MILLIS
    }
}
