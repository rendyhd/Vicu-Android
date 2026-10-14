package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.data.repository.TaskRepositoryHarness.Companion.serviceUnavailable
import com.rendyhd.vicu.domain.model.TaskReminder
import com.rendyhd.vicu.util.NetworkResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * An offline edit writes an optimistic row to Room until the queued action syncs. That row must
 * keep everything the edit did not touch: the server never sees those fields in the patch, so
 * dropping them locally only loses data (subtasks pop out as top-level tasks, attachments vanish).
 */
class TaskRepositoryOptimisticUpdateTest {

    private val original = cachedTaskEntity(id = 42)

    private fun offlineHarness(entity: TaskEntity = original) =
        TaskRepositoryHarness(taskDao = FakeTaskDao(listOf(entity))) { serviceUnavailable() }

    /** Completing a parent cascades to open subtasks; these tests are about the parent row only. */
    private fun TaskEntity.withDoneChildren() =
        copy(relatedTasksJson = relatedTasksJson.replace("\"done\":false", "\"done\":true"))

    @Test
    fun `an offline update keeps related tasks and attachments`() = runTest {
        val h = offlineHarness()
        val domain = with(h.mapper) { original.toDomain() }

        val result = h.repository.update(domain.copy(title = "Edited", priority = 4))

        assertIs<NetworkResult.Success<*>>(result)
        val stored = assertNotNull(h.taskDao.entity(42))
        assertEquals("Edited", stored.title)
        assertEquals(4, stored.priority)
        assertEquals(original.relatedTasksJson, stored.relatedTasksJson)
        assertEquals(original.attachmentsJson, stored.attachmentsJson)
        assertTrue(stored.relatedTasksJson.contains("Child"))
        assertTrue(stored.attachmentsJson.contains("a.png"))
    }

    @Test
    fun `an update keeps labels, ordering and creation metadata that the patch cannot carry`() = runTest {
        val h = offlineHarness()
        val domain = with(h.mapper) { original.toDomain() }

        h.repository.update(domain.copy(description = "Changed"))

        val stored = assertNotNull(h.taskDao.entity(42))
        assertEquals("Changed", stored.description)
        assertEquals(original.labelsJson, stored.labelsJson)
        assertEquals(original.position, stored.position)
        assertEquals(original.created, stored.created)
        assertEquals(original.updated, stored.updated)
        assertEquals(original.createdById, stored.createdById)
        assertEquals(original.createdByUsername, stored.createdByUsername)
    }

    @Test
    fun `an update that changes reminders replaces the cached reminders`() = runTest {
        val h = offlineHarness()
        val domain = with(h.mapper) { original.toDomain() }
        val reminder = TaskReminder(reminder = "2026-10-08T08:00:00Z")

        h.repository.update(domain.copy(reminders = listOf(reminder)))

        val stored = assertNotNull(h.taskDao.entity(42))
        assertTrue(stored.remindersJson.contains("2026-10-08T08:00:00Z"))
        assertEquals(original.relatedTasksJson, stored.relatedTasksJson)
    }

    @Test
    fun `an offline completion keeps related tasks and attachments`() = runTest {
        val h = offlineHarness(original.withDoneChildren())
        val before = assertNotNull(h.taskDao.entity(42))
        val domain = with(h.mapper) { before.toDomain() }

        val result = h.repository.toggleDone(domain)

        assertIs<NetworkResult.Success<*>>(result)
        val stored = assertNotNull(h.taskDao.entity(42))
        assertTrue(stored.done)
        assertEquals(before.relatedTasksJson, stored.relatedTasksJson)
        assertEquals(before.attachmentsJson, stored.attachmentsJson)
        assertEquals(before.labelsJson, stored.labelsJson)
    }

    @Test
    fun `completing from an older copy of the task only changes done`() = runTest {
        val h = offlineHarness(original.withDoneChildren())
        val stale = with(h.mapper) { original.withDoneChildren().toDomain() }.copy(title = "Stale title", priority = 0)

        h.repository.toggleDone(stale)

        val stored = assertNotNull(h.taskDao.entity(42))
        assertTrue(stored.done)
        assertEquals("Original", stored.title)
        assertEquals(2, stored.priority)
    }

    @Test
    fun `completing a queued offline-created task keeps its cached relations`() = runTest {
        val temp = cachedTaskEntity(id = -5).withDoneChildren()
        val h = offlineHarness(temp)
        val domain = with(h.mapper) { temp.toDomain() }

        h.repository.toggleDone(domain)

        val stored = assertNotNull(h.taskDao.entity(-5))
        assertTrue(stored.done)
        assertEquals(temp.relatedTasksJson, stored.relatedTasksJson)
        assertEquals(temp.attachmentsJson, stored.attachmentsJson)
    }
}
