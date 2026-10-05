package com.rendyhd.vicu.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskWidgetStateTest {
    @Test
    fun pendingCompletionStaysHiddenAcrossRefreshes() {
        val pending = setOf(1L)
        val state = TaskWidgetState(
            tasks = listOf(WidgetTaskItem(1, "Parent"), WidgetTaskItem(2, "Other")),
            totalCount = 2,
            pendingCompletionIds = pending,
        )

        val hidden = state.hidePendingCompletions()
        assertEquals(listOf(2L), hidden.tasks.map { it.id })
        assertEquals(1, hidden.totalCount)
        assertEquals(pending, hidden.pendingCompletionIds)

        val refreshed = state.copy(tasks = state.tasks, totalCount = 2).hidePendingCompletions()
        assertEquals(listOf(2L), refreshed.tasks.map { it.id })
        assertEquals(1, refreshed.totalCount)
    }

    @Test
    fun missingPendingTaskDoesNotReduceCountAgain() {
        val state = TaskWidgetState(
            tasks = listOf(WidgetTaskItem(2, "Other")),
            totalCount = 1,
            pendingCompletionIds = setOf(1L),
        )

        assertEquals(state, state.hidePendingCompletions())
        assertEquals(state.tasks, state.copy(pendingCompletionIds = emptySet()).hidePendingCompletions().tasks)
    }
}
