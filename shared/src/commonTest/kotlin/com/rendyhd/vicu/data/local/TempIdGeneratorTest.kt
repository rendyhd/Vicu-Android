package com.rendyhd.vicu.data.local

import com.rendyhd.vicu.data.repository.FakeTaskDao
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.data.repository.TaskRepositoryHarness
import com.rendyhd.vicu.data.repository.TaskRepositoryHarness.Companion.serviceUnavailable
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.NetworkResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TempIdGeneratorTest {

    @Test
    fun `ids are negative and strictly decreasing`() = runTest {
        val ids = TempIdGenerator(InMemoryPreferencesDataStore())

        assertEquals(listOf(-1L, -2L, -3L), listOf(ids.next(), ids.next(), ids.next()))
    }

    @Test
    fun `a restart carries on where the last run stopped`() = runTest {
        val store = InMemoryPreferencesDataStore()
        val firstRun = TempIdGenerator(store)
        repeat(5) { firstRun.next() }

        val afterRestart = TempIdGenerator(store)

        assertEquals(-6L, afterRestart.next())
        assertEquals(-6L, afterRestart.lastIssued())
    }

    @Test
    fun `nothing is issued before the first request`() = runTest {
        assertNull(TempIdGenerator(InMemoryPreferencesDataStore()).lastIssued())
    }

    @Test
    fun `ids requested at the same time are all different`() = runTest {
        val ids = TempIdGenerator(InMemoryPreferencesDataStore())

        val issued = (1..200).map { async { ids.next() } }.awaitAll()

        assertEquals(200, issued.toSet().size)
        assertEquals(-200L, issued.min())
    }

    @Test
    fun `ids stay clear of the ones the old time-based scheme issued`() = runTest {
        // The old scheme counted down from minus the start time in seconds: about -1.8 billion.
        val legacyRange = -1_700_000_000L
        val ids = TempIdGenerator(InMemoryPreferencesDataStore())

        repeat(1_000) { assertTrue(ids.next() > legacyRange) }
    }

    @Test
    fun `tasks created offline across restarts never share a temp id`() = runTest {
        val store = InMemoryPreferencesDataStore()
        val taskDao = FakeTaskDao()
        val pendingDao = com.rendyhd.vicu.data.repository.FakePendingActionDao()
        val offlineTask = Task(id = 0, title = "Offline", projectId = 7)

        val created = mutableListOf<Long>()
        // Three "process lifetimes" in the same second, each creating several tasks while offline.
        repeat(3) {
            val run = TaskRepositoryHarness(
                taskDao = taskDao,
                pendingActionDao = pendingDao,
                tempIds = TempIdGenerator(store),
            ) { serviceUnavailable() }
            repeat(4) {
                val result = run.repository.create(offlineTask)
                created += (result as NetworkResult.Success).data.id
            }
        }

        assertEquals(12, created.toSet().size, "every task got its own temp id: $created")
        assertTrue(created.all { it < 0 })
        assertEquals(12, taskDao.snapshot().size, "no queued task overwrote another")
        assertEquals(12, pendingDao.snapshot().map { it.entityId }.toSet().size)
    }
}
