package com.rendyhd.vicu.ui.screens.shared

import com.rendyhd.vicu.domain.model.Task
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CompletionHoldTest {

    private fun task(id: Long, projectId: Long = 1) = Task(id = id, title = "Task $id", projectId = projectId)

    private val a = task(1)
    private val b = task(2)
    private val c = task(3)
    private val d = task(4)

    private fun ids(list: List<Task>) = list.map { it.id }

    @Test
    fun `a completed row stays in its place after it leaves the stored list`() = runTest {
        val hold = CompletionHold(backgroundScope)
        hold.merge(listOf(a, b, c, d))

        assertTrue(hold.hold(b))
        // Room now has the task done, so the open-tasks query no longer returns it.
        val shown = hold.merge(listOf(a, c, d))

        assertEquals(listOf(1L, 2L, 3L, 4L), ids(shown))
    }

    @Test
    fun `several held rows keep their places relative to each other`() = runTest {
        val hold = CompletionHold(backgroundScope)
        hold.merge(listOf(a, b, c, d))
        hold.hold(d)
        hold.hold(b)

        assertEquals(listOf(1L, 2L, 3L, 4L), ids(hold.merge(listOf(a, c))))
    }

    @Test
    fun `a row is released after the hold time and then disappears`() = runTest {
        val hold = CompletionHold(backgroundScope, holdMillis = 5_000)
        hold.merge(listOf(a, b))
        hold.hold(a)
        val remaining = listOf(b)

        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(listOf(1L, 2L), ids(hold.merge(remaining)))

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(2L), ids(hold.merge(remaining)))
    }

    @Test
    fun `holding a row again restarts its timer`() = runTest {
        val hold = CompletionHold(backgroundScope, holdMillis = 5_000)
        hold.merge(listOf(a, b))
        hold.hold(a)
        advanceTimeBy(4_000)
        hold.merge(listOf(a, b))
        hold.hold(a)
        advanceTimeBy(4_000)
        runCurrent()

        assertEquals(setOf(1L), hold.state.value.keys, "the first timer must not release the second hold")
    }

    @Test
    fun `releasing a row removes it at once`() = runTest {
        val hold = CompletionHold(backgroundScope)
        hold.merge(listOf(a, b))
        hold.hold(a)

        hold.release(1)

        assertEquals(listOf(2L), ids(hold.merge(listOf(b))))
        assertTrue(hold.state.value.isEmpty())
    }

    @Test
    fun `leaving the screen releases everything and stops the timers`() = runTest {
        val hold = CompletionHold(backgroundScope)
        hold.merge(listOf(a, b, c))
        hold.hold(a)
        hold.hold(c)

        hold.releaseAll()

        assertTrue(hold.state.value.isEmpty())
        assertEquals(listOf(2L), ids(hold.merge(listOf(b))))
    }

    @Test
    fun `a task that is not a row of the screen is not held`() = runTest {
        val hold = CompletionHold(backgroundScope)
        hold.merge(listOf(a, b))

        assertFalse(hold.hold(task(99)), "a subtask completed inside its parent's row")
        assertTrue(hold.state.value.isEmpty())
        assertEquals(listOf(1L, 2L), ids(hold.merge(listOf(a, b))))
    }

    @Test
    fun `nothing is held before the screen has shown a list`() = runTest {
        val hold = CompletionHold(backgroundScope)

        assertFalse(hold.hold(a))
    }

    @Test
    fun `a held row that is still in the stored list is not duplicated`() = runTest {
        val hold = CompletionHold(backgroundScope)
        hold.merge(listOf(a, b))
        hold.hold(a)

        // The stored task has not changed yet (the request is still running).
        assertEquals(listOf(1L, 2L), ids(hold.merge(listOf(a, b))))
        // A repeating task comes back open: it is in the list again, once.
        assertEquals(listOf(1L, 2L), ids(hold.merge(listOf(a.copy(dueDate = "2026-10-09T10:00:00Z"), b))))
    }

    @Test
    fun `an index past the end of a shorter list puts the row last`() = runTest {
        val hold = CompletionHold(backgroundScope)
        hold.merge(listOf(a, b, c, d))
        hold.hold(d)

        assertEquals(listOf(1L, 4L), ids(hold.merge(listOf(a))))
    }

    @Test
    fun `lists of different scopes are held separately`() = runTest {
        val hold = CompletionHold(backgroundScope)
        val p1a = task(1, projectId = 10)
        val p1b = task(2, projectId = 10)
        val p2a = task(3, projectId = 20)
        val p2b = task(4, projectId = 20)
        hold.merge(listOf(p1a, p1b), listScope = 10)
        hold.merge(listOf(p2a, p2b), listScope = 20)

        hold.hold(p2a)
        hold.hold(p1b)

        assertEquals(listOf(1L, 2L), ids(hold.merge(listOf(p1a), listScope = 10)))
        assertEquals(listOf(3L, 4L), ids(hold.merge(listOf(p2b), listScope = 20)))
    }

    @Test
    fun `held ids are published for the screen to draw as just changed`() = runTest {
        val hold = CompletionHold(backgroundScope)
        val seen = mutableListOf<Set<Long>>()
        backgroundScope.launch { hold.heldIds.collect { seen += it } }
        runCurrent()
        hold.merge(listOf(a, b))

        hold.hold(a)
        runCurrent()
        hold.release(1)
        runCurrent()

        assertEquals(listOf(emptySet(), setOf(1L), emptySet()), seen)
    }

    @Test
    fun `an undone row stays in place but is no longer drawn as changed`() = runTest {
        val hold = CompletionHold(backgroundScope)
        val seen = mutableListOf<Set<Long>>()
        backgroundScope.launch { hold.heldIds.collect { seen += it } }
        runCurrent()
        hold.merge(listOf(a, b))
        hold.hold(a)
        runCurrent()

        hold.undoing(1)
        runCurrent()

        assertEquals(listOf(emptySet(), setOf(1L), emptySet()), seen)
        // The stored task has not been reopened yet, so the row must not vanish meanwhile.
        assertEquals(listOf(1L, 2L), ids(hold.merge(listOf(b))))
        hold.release(1)
        assertEquals(listOf(2L), ids(hold.merge(listOf(b))))
    }
}
