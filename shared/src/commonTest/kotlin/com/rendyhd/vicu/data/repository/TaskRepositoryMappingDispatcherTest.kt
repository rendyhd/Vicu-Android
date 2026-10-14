package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.data.repository.TaskRepositoryHarness.Companion.serviceUnavailable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The live lists map Room rows to domain tasks on the repository's mapping dispatcher, not on the
 * collector's (A-UI-19). The dispatcher below runs work on the spot and counts what it was asked to run.
 */
class TaskRepositoryMappingDispatcherTest {

    private class CountingDispatcher : CoroutineDispatcher() {
        var dispatches = 0
            private set

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches++
            block.run()
        }
    }

    private val rows = listOf(
        TaskEntity(id = 1, title = "Open", projectId = 7),
        TaskEntity(id = 2, title = "Carrier", description = "<!-- vicu-routine:v1:e30 -->", done = true, projectId = 7),
    )

    private suspend fun <T> assertRunsOnMappingDispatcher(name: String, flow: (TaskRepositoryHarness) -> Flow<T>) {
        val dispatcher = CountingDispatcher()
        val h = TaskRepositoryHarness(
            taskDao = FakeTaskDao(rows),
            mappingDispatcher = dispatcher,
        ) { serviceUnavailable() }

        flow(h).first()

        assertTrue(dispatcher.dispatches > 0, "$name is mapped on the mapping dispatcher")
    }

    @Test
    fun `the task lists are mapped on the mapping dispatcher`() = runTest {
        assertRunsOnMappingDispatcher("inbox") { it.repository.getInboxTasks(7) }
        assertRunsOnMappingDispatcher("today") { it.repository.getTodayTasks() }
        assertRunsOnMappingDispatcher("upcoming") { it.repository.getUpcomingTasks() }
        assertRunsOnMappingDispatcher("anytime") { it.repository.getAnytimeTasks(1) }
        assertRunsOnMappingDispatcher("logbook") { it.repository.getLogbookTasks() }
        assertRunsOnMappingDispatcher("project") { it.repository.getByProjectId(7) }
        assertRunsOnMappingDispatcher("task") { it.repository.getById(1) }
        assertRunsOnMappingDispatcher("all open") { it.repository.getAllOpenTasksFlat() }
        assertRunsOnMappingDispatcher("all") { it.repository.getAllTasksFlat() }
    }

    @Test
    fun `a metadata row is dropped by its flag, whatever the query returned`() = runTest {
        val h = TaskRepositoryHarness(taskDao = FakeTaskDao(rows)) { serviceUnavailable() }

        assertEquals(listOf(1L), h.repository.getAllTasksFlat().first().map { it.id })
        assertEquals(emptyList(), h.repository.getByIds(setOf(2L)).map { it.id })
    }
}
