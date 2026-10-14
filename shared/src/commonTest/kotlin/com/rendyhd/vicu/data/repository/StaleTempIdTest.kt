package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.local.entity.LabelEntity
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.NetworkResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A screen (or a notification, or a widget) can still hold a task under the temporary id it had
 * while offline after the sync replayed its create and swapped in the created task. A change made
 * with that id goes to the created task instead of making a ghost row whose change is lost.
 */
class StaleTempIdTest {

    /** A task created offline and then synced: the task as the screen still holds it, and its server id. */
    private suspend fun TaskWriteRig.createdOfflineThenSynced(): Pair<Task, Long> {
        down = true
        val local = (tasks.repository.create(Task(id = 0, title = "Buy milk", projectId = 7)) as NetworkResult.Success).data
        down = false
        assertTrue(sync.engine.performSync())
        val realId = server.rows.values.single { it.title == "Buy milk" }.id
        assertNull(taskDao.entity(local.id), "the local row was replaced")
        assertEquals(realId, tasks.tempIds.realIdFor(local.id))
        return local to realId
    }

    @Test
    fun `an edit made with the temporary id lands on the created task`() = runTest {
        val rig = TaskWriteRig()
        val (stale, realId) = rig.createdOfflineThenSynced()

        val result = rig.tasks.repository.update(stale.copy(title = "Buy oat milk"))

        assertIs<NetworkResult.Success<*>>(result)
        assertEquals("Buy oat milk", rig.server.row(realId).title)
        assertNull(rig.taskDao.entity(stale.id), "no ghost row under the old id")
        assertTrue(rig.queued().isEmpty())
        rig.sync.close()
    }

    @Test
    fun `completing and deleting with the temporary id act on the created task`() = runTest {
        val rig = TaskWriteRig()
        val (stale, realId) = rig.createdOfflineThenSynced()

        rig.tasks.repository.setDone(stale.id, true)
        assertTrue(rig.server.row(realId).done)

        rig.tasks.repository.delete(stale.id)
        assertTrue(realId !in rig.server.rows)
        assertNull(rig.taskDao.entity(stale.id))
        rig.sync.close()
    }

    @Test
    fun `a label added with the temporary id goes to the created task`() = runTest {
        val rig = TaskWriteRig()
        rig.labelDao.upsert(LabelEntity(id = 5, title = "home"))
        val (stale, realId) = rig.createdOfflineThenSynced()

        rig.labels.addToTask(stale.id, 5)

        assertEquals(listOf("POST /tasks/$realId/labels"), rig.labelRequests)
        rig.sync.close()
    }

    @Test
    fun `a change queued with the temporary id after the swap is sent to the created task`() = runTest {
        val rig = TaskWriteRig()
        val (stale, realId) = rig.createdOfflineThenSynced()
        rig.pendingActionDao.insert(
            PendingActionEntity(
                entityType = "task", entityId = stale.id, actionType = "update", payload = """{"title":"Late edit"}""",
                createdAt = "2026-10-06T10:00:00Z", updatedAt = "2026-10-06T10:00:00Z",
            ),
        )

        assertTrue(rig.sync.engine.performSync())

        assertEquals("Late edit", rig.server.row(realId).title)
        assertTrue(rig.queued().isEmpty())
        rig.sync.close()
    }

    @Test
    fun `an edit of a local task that is gone writes nothing`() = runTest {
        val rig = TaskWriteRig()

        val result = rig.tasks.repository.update(Task(id = -9, title = "Deleted here", projectId = 7))

        assertIs<NetworkResult.Error>(result)
        assertNull(rig.taskDao.entity(-9))
        assertTrue(rig.queued().isEmpty())
    }
}
