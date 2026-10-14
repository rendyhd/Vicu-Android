package com.rendyhd.vicu.ui.screens.shared

import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Where a completed row goes when its hold ends: it has left its list and joins the app-wide toast. */
interface CompletionToast {
    fun collapsed(taskId: Long)
}

/** No toast: screens built without one (tests, previews) just drop the row. */
object NoCompletionToast : CompletionToast {
    override fun collapsed(taskId: Long) = Unit
}

/**
 * The "Completed, Undo" snackbar of the contract (docs/cross-app-semantics-v1.md section 7.3).
 * The first row that collapses posts "Completed"; while that message is still within
 * [CompletionHold.TOAST_MILLIS] a further row replaces it with "2 completed", "3 completed" and
 * the 6 s clock restarts (the app scaffold shows only the newest message). Undo reopens every row
 * the message covers. A snackbar has no pointer or focus to pause it; the scaffold lengthens the
 * time when an accessibility service asks for more.
 *
 * The machine decides what the message says and covers; the message then carries its own task
 * ids. The machine's toast ends after 6 s, but with an accessibility service the snackbar lasts
 * longer, and Undo on it must still reopen the tasks it names.
 */
class CompletionToastCenter(
    private val messages: AppMessages,
    private val taskRepository: TaskRepository,
    private val time: TimeSource,
    private val scope: CoroutineScope,
) : CompletionToast {
    private val machine = CompletionHoldMachine()

    private fun nowMillis() = time.now().toEpochMilliseconds()

    override fun collapsed(taskId: Long) {
        val at = nowMillis()
        // A row only collapses after a completion made now. One the machine still has as collapsed
        // was reopened somewhere else since (the machine cannot see that) and completed again.
        machine.forget(taskId, at)
        machine.collapseNow(taskId, at)
        val text = machine.snapshot(at).toast ?: return
        val ids = machine.toastIds()
        messages.post(text, CompletionHold.TOAST_ACTION, CompletionHold.TOAST_MILLIS) { undo(ids) }
    }

    /** Undo on the message that named [ids]: they are open again, so the machine forgets them too. */
    private fun undo(ids: List<Long>) {
        if (ids.isEmpty()) return
        val at = nowMillis()
        ids.forEach { machine.forget(it, at) }
        scope.launch {
            val failed = ids.count { taskRepository.setDone(it, false) is NetworkResult.Error }
            if (failed > 0) {
                messages.post("Could not reopen $failed of ${ids.size} ${if (ids.size == 1) "task" else "tasks"}")
            }
        }
    }
}
