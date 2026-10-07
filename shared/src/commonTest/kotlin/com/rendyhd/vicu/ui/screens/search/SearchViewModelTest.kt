package com.rendyhd.vicu.ui.screens.search

import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.FakeLabelRepository
import com.rendyhd.vicu.ui.FakeProjectRepository
import com.rendyhd.vicu.ui.FakeTaskRepository
import com.rendyhd.vicu.ui.screens.shared.CompletionHold
import com.rendyhd.vicu.ui.screens.shared.SEARCH_REFRESH_DEBOUNCE_MS
import com.rendyhd.vicu.util.RelationKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Search shows the cached matches at once, in an open and a completed section, while the server
 * is asked in the background (debounced, cancelled by a new text) and only updates the cache
 * (A-UI-16, UI-22).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    @BeforeTest
    fun setMain() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun resetMain() {
        Dispatchers.resetMain()
    }

    private class Rig {
        val tasks = FakeTaskRepository()
        val projects = FakeProjectRepository(listOf(Project(id = 7, title = "Home")))
        val viewModel = SearchViewModel(tasks, projects, FakeLabelRepository())
        val state get() = viewModel.uiState.value
    }

    private fun task(id: Long, title: String, done: Boolean = false, projectId: Long = 7, description: String = "") =
        Task(id = id, title = title, done = done, projectId = projectId, description = description)

    private fun TestScope.rig(vararg tasks: Task): Rig = Rig().also { rig ->
        tasks.forEach(rig.tasks::put)
        runCurrent()
    }

    @Test
    fun `the cached matches show at once, before the server was asked`() = runTest {
        val rig = rig(task(1, "Buy milk"), task(2, "Walk the dog"))
        rig.tasks.refreshGate = CompletableDeferred()

        rig.viewModel.onQueryChanged("milk")
        runCurrent()

        assertEquals(listOf(1L), rig.state.results.map { it.id })
        assertTrue(rig.state.resultsReady)
        assertEquals(emptyList(), rig.tasks.refreshes, "the server is not asked until the text has rested")

        advanceTimeBy(SEARCH_REFRESH_DEBOUNCE_MS + 1)

        assertEquals(listOf(mapOf("q" to "milk")), rig.tasks.refreshes)
        assertTrue(rig.state.isRefreshing)
        assertEquals(listOf(1L), rig.state.results.map { it.id }, "the cached matches stay while the server answers")
    }

    @Test
    fun `the server only updates the cache, and the list follows it`() = runTest {
        val rig = rig(task(1, "Buy milk"))
        rig.viewModel.onQueryChanged("milk")
        advanceTimeBy(SEARCH_REFRESH_DEBOUNCE_MS + 1)
        assertFalse(rig.state.isRefreshing)

        // The refresh brought a task another device created into the cache.
        rig.tasks.put(task(2, "Oat milk"))
        runCurrent()

        assertEquals(setOf(1L, 2L), rig.state.results.map { it.id }.toSet())
    }

    @Test
    fun `the title and the description are searched`() = runTest {
        val rig = rig(task(1, "Call mum", description = "<p>ask about the milk</p>"), task(2, "Unrelated"))

        rig.viewModel.onQueryChanged("milk")
        runCurrent()

        assertEquals(listOf(1L), rig.state.results.map { it.id })
    }

    @Test
    fun `completed tasks are a section of their own`() = runTest {
        val rig = rig(task(1, "Milk run", done = true), task(2, "Milk bottle"))

        rig.viewModel.onQueryChanged("milk")
        runCurrent()

        assertEquals(listOf(2L), rig.state.results.map { it.id })
        assertEquals(listOf(1L), rig.state.completedResults.map { it.id })
    }

    @Test
    fun `the results of the previous text are gone the moment the text changes`() = runTest {
        val rig = rig(task(1, "Buy milk"), task(2, "Walk the dog"))
        rig.viewModel.onQueryChanged("milk")
        runCurrent()
        assertEquals(listOf(1L), rig.state.results.map { it.id })

        rig.viewModel.onQueryChanged("dog")

        // Before anything ran: nothing from "milk" is shown for "dog".
        assertEquals(emptyList(), rig.state.results)
        assertFalse(rig.state.resultsReady)
        runCurrent()
        assertEquals(listOf(2L), rig.state.results.map { it.id })
    }

    @Test
    fun `a text with nothing cached shows nothing, ready, while the server is still asked`() = runTest {
        val rig = rig(task(1, "Buy milk"))
        rig.tasks.refreshGate = CompletableDeferred()

        rig.viewModel.onQueryChanged("zebra")
        runCurrent()
        advanceTimeBy(SEARCH_REFRESH_DEBOUNCE_MS + 1)

        assertEquals(emptyList(), rig.state.results)
        assertTrue(rig.state.resultsReady)
        assertTrue(rig.state.isRefreshing, "the screen can say searching instead of no results")
    }

    @Test
    fun `typing quickly asks the server once, about the last text`() = runTest {
        val rig = rig()

        listOf("m", "mi", "mil", "milk").forEach {
            rig.viewModel.onQueryChanged(it)
            advanceTimeBy(60)
        }
        advanceTimeBy(SEARCH_REFRESH_DEBOUNCE_MS + 1)

        assertEquals(listOf(mapOf("q" to "milk")), rig.tasks.refreshes)
    }

    @Test
    fun `a new text cancels the request that is in flight`() = runTest {
        val rig = rig()
        rig.tasks.refreshGate = CompletableDeferred()

        rig.viewModel.onQueryChanged("ab")
        advanceTimeBy(SEARCH_REFRESH_DEBOUNCE_MS + 1)
        assertEquals(1, rig.tasks.refreshes.size)

        rig.viewModel.onQueryChanged("abc")
        runCurrent()
        assertTrue(rig.state.isRefreshing, "the new text is already being searched for")
        advanceTimeBy(SEARCH_REFRESH_DEBOUNCE_MS + 1)
        rig.tasks.refreshGate!!.complete(Unit)
        runCurrent()

        assertEquals(listOf(mapOf("q" to "ab"), mapOf("q" to "abc")), rig.tasks.refreshes)
        assertEquals(1, rig.tasks.completedRefreshes, "only the answer for the last text arrived")
        assertFalse(rig.state.isRefreshing)
    }

    @Test
    fun `clearing the text empties the screen and stops the request`() = runTest {
        val rig = rig(task(1, "Buy milk"))
        rig.tasks.refreshGate = CompletableDeferred()
        rig.viewModel.onQueryChanged("milk")
        advanceTimeBy(SEARCH_REFRESH_DEBOUNCE_MS + 1)
        assertTrue(rig.state.isRefreshing)

        rig.viewModel.onQueryChanged("")
        runCurrent()
        rig.tasks.refreshGate!!.complete(Unit)
        runCurrent()

        assertEquals(emptyList(), rig.state.results)
        assertFalse(rig.state.isRefreshing)
        assertEquals(0, rig.tasks.completedRefreshes)
    }

    @Test
    fun `only blanks asks the server for nothing`() = runTest {
        val rig = rig()

        rig.viewModel.onQueryChanged("   ")
        advanceTimeBy(1_000)

        assertEquals(emptyList(), rig.tasks.refreshes)
        assertFalse(rig.state.isRefreshing)
    }

    @Test
    fun `a trailing space keeps the results on screen and does not ask again`() = runTest {
        val rig = rig(task(1, "Buy milk"))
        rig.viewModel.onQueryChanged("milk")
        advanceTimeBy(SEARCH_REFRESH_DEBOUNCE_MS + 1)

        rig.viewModel.onQueryChanged("milk ")
        runCurrent()
        advanceTimeBy(1_000)

        assertEquals(listOf(1L), rig.state.results.map { it.id })
        assertEquals(1, rig.tasks.refreshes.size)
    }

    @Test
    fun `tasks of a project that is not shown are left out`() = runTest {
        val rig = rig(task(1, "Milk at home"), task(2, "Milk in an archived project", projectId = 99))

        rig.viewModel.onQueryChanged("milk")
        runCurrent()

        assertEquals(listOf(1L), rig.state.results.map { it.id })
    }

    @Test
    fun `a matching subtask shows by itself when its parent did not match`() = runTest {
        val parent = task(1, "Groceries")
        val child = task(2, "Milk").copy(relatedTasks = mapOf(RelationKind.PARENTTASK to listOf(parent)))
        val rig = rig(parent, child)

        rig.viewModel.onQueryChanged("milk")
        runCurrent()

        assertEquals(listOf(2L), rig.state.results.map { it.id })
    }

    @Test
    fun `a task completed here stays in place for a moment, then moves to the completed section`() = runTest {
        val milk = task(1, "Milk bottle")
        val rig = rig(milk, task(2, "Milk shake"))
        rig.viewModel.onQueryChanged("milk")
        runCurrent()

        rig.viewModel.toggleDone(milk)
        runCurrent()

        assertEquals(setOf(1L, 2L), rig.state.results.map { it.id }.toSet(), "the row stays, struck through")
        assertEquals(setOf(1L), rig.state.completedTaskIds)
        assertEquals(emptyList(), rig.state.completedResults, "not shown twice")

        advanceTimeBy(CompletionHold.DEFAULT_HOLD_MILLIS + 1)

        assertEquals(listOf(2L), rig.state.results.map { it.id })
        assertEquals(listOf(1L), rig.state.completedResults.map { it.id })
    }
}
