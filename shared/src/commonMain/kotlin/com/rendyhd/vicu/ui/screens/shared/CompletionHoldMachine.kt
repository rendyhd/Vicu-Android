package com.rendyhd.vicu.ui.screens.shared

/** What the machine says is on screen at one moment (the contract's `held`, `collapsed` and `toast`). */
data class CompletionHoldSnapshot(
    /** Row ids still shown in their list as done, ascending. */
    val held: List<Long>,
    /** Row ids gone from their list (still done on the server), ascending. */
    val collapsed: List<Long>,
    /** The toast text, or null when no toast is showing. */
    val toast: String?,
)

/**
 * The completion hold of docs/cross-app-semantics-v1.md section 7 as a pure state machine, the
 * same rules as the desktop's `src/shared/completion-hold.ts`; the `completion` vectors of
 * `test-fixtures/cross-app-semantics-v1.json` run against it (`CompletionHoldMachineTest`).
 *
 * A completed row is held for [CompletionHold.HOLD_MILLIS] after the later of the completion and
 * the last moment it stopped being engaged (pointer over it, or focus on it), then collapsed.
 * Collapsed rows join one toast ("Completed", "3 completed") that lives
 * [CompletionHold.TOAST_MILLIS] on the same engagement rule; its Undo reopens every row it covers.
 * Leaving the view collapses every held row at once.
 *
 * No timers and no clock: every method takes the time `at` (milliseconds, any origin, never going
 * backwards) and first fires the timers due up to and including that time, so a timer due at the
 * same instant as an event runs before it. On Android the phone has no pointer, so the engagement
 * calls are unused there; the toast half is what the app runs ([CompletionToastCenter]).
 */
class CompletionHoldMachine(
    private val holdMillis: Long = CompletionHold.HOLD_MILLIS,
    private val toastMillis: Long = CompletionHold.TOAST_MILLIS,
) {
    private class ToastState(
        val ids: MutableList<Long>,
        /** The toast clock runs from here: its creation, the last row that joined, or the end of engagement. */
        var since: Long,
        var pointer: Boolean = false,
        var focus: Boolean = false,
    )

    // Engagement is tracked from before a completion, so a row completed under the pointer is engaged.
    private val pointer = HashSet<Long>()
    private val focus = HashSet<Long>()

    /** Held row id to the moment its hold started (the completion or the end of the last engagement). */
    private val held = HashMap<Long, Long>()
    private val collapsed = HashSet<Long>()
    private var toast: ToastState? = null
    private var now = Long.MIN_VALUE

    /** The state at [at], after the timers due by then have fired. */
    fun snapshot(at: Long): CompletionHoldSnapshot {
        advance(at)
        return CompletionHoldSnapshot(
            held = held.keys.sorted(),
            collapsed = collapsed.sorted(),
            toast = toast?.let { CompletionHold.toastText(it.ids.size) },
        )
    }

    /** The ids the toast currently covers (what its Undo reopens). */
    fun toastIds(): List<Long> = toast?.ids?.toList().orEmpty()

    /** An open row was completed. A row that is already held or collapsed keeps its first deadline. */
    fun complete(id: Long, at: Long) {
        advance(at)
        if (id in held || id in collapsed) return
        held[id] = at
    }

    /** Unchecking a held row: it is open again, its hold is cancelled, no toast. False when it was not held. */
    fun undoRow(id: Long, at: Long): Boolean {
        advance(at)
        return held.remove(id) != null
    }

    fun hover(id: Long, on: Boolean, at: Long) = engage(pointer, id, on, at)

    fun focusRow(id: Long, on: Boolean, at: Long) = engage(focus, id, on, at)

    fun hoverToast(on: Boolean, at: Long) {
        advance(at)
        val t = toast ?: return
        t.pointer = on
        releaseToast(at)
    }

    fun focusToast(on: Boolean, at: Long) {
        advance(at)
        val t = toast ?: return
        t.focus = on
        releaseToast(at)
    }

    /** The user left the view: every held row collapses now, engaged or not. */
    fun navigate(at: Long) {
        advance(at)
        for (id in held.keys.sorted()) collapse(id, at)
    }

    /**
     * For a host that runs its own row timers (the Android screens do): the row [id] left its list
     * now and joins the toast, as if its hold had ended at [at]. Does nothing for a row that is
     * already collapsed.
     */
    fun collapseNow(id: Long, at: Long) {
        advance(at)
        if (id in collapsed) return
        held.remove(id)
        collapse(id, at)
    }

    /**
     * Undo on the toast: every row it covers is open again and the toast goes. Rows that are still
     * held are not touched. Returns the ids to reopen (empty when no toast is showing).
     */
    fun undoToast(at: Long): List<Long> {
        advance(at)
        val t = toast ?: return emptyList()
        val ids = t.ids.toList()
        collapsed.removeAll(ids.toSet())
        toast = null
        return ids
    }

    /** The toast is gone for another reason (replaced by another message): the rows stay collapsed. */
    fun dismissToast(at: Long) {
        advance(at)
        toast = null
    }

    /**
     * A row is open or gone for a reason outside the contract (a failed request rolled it back):
     * drop it from the hold, the collapsed set and the toast. The toast goes when nothing is left.
     */
    fun forget(id: Long, at: Long) {
        advance(at)
        held.remove(id)
        collapsed.remove(id)
        toast?.let { t ->
            t.ids.remove(id)
            if (t.ids.isEmpty()) toast = null
        }
    }

    /** Fire every timer due up to and including [at], in time order; rows before the toast at one instant. */
    fun advance(at: Long) {
        val to = if (at < now) now else at
        now = to
        while (true) {
            var rowId: Long? = null
            var rowDue = Long.MAX_VALUE
            for ((id, since) in held) {
                if (isRowEngaged(id)) continue
                val due = since + holdMillis
                if (due < rowDue || (due == rowDue && rowId != null && id < rowId)) {
                    rowDue = due
                    rowId = id
                }
            }
            val t = toast
            val toastDue = if (t != null && !t.pointer && !t.focus) t.since + toastMillis else Long.MAX_VALUE
            if (rowId != null && rowDue <= to && rowDue <= toastDue) {
                collapse(rowId, rowDue)
            } else if (toastDue <= to) {
                toast = null
            } else {
                return
            }
        }
    }

    private fun isRowEngaged(id: Long) = id in pointer || id in focus

    private fun engage(set: MutableSet<Long>, id: Long, on: Boolean, at: Long) {
        advance(at)
        // A collapsed row is no longer on screen; a late "left" from its removed element means nothing.
        if (id in collapsed) return
        val was = isRowEngaged(id)
        if (on) set.add(id) else set.remove(id)
        if (was && !isRowEngaged(id) && id in held) held[id] = at
    }

    private fun releaseToast(at: Long) {
        val t = toast ?: return
        if (!t.pointer && !t.focus) t.since = at
    }

    private fun collapse(id: Long, at: Long) {
        held.remove(id)
        collapsed.add(id)
        // The element is going away; it will never report the pointer or focus leaving.
        pointer.remove(id)
        focus.remove(id)
        val t = toast
        if (t != null) {
            t.ids.add(id)
            t.since = at
        } else {
            toast = ToastState(mutableListOf(id), at)
        }
    }
}
