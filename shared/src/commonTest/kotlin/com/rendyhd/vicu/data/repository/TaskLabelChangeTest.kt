package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.local.entity.LabelEntity
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.RelationKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Labels on tasks and labels that only exist on this device, and what a label or relation change
 * reads back from the server.
 */
class TaskLabelChangeTest {

    private suspend fun TaskWriteRig.createOffline(title: String): Long {
        down = true
        val created = tasks.repository.create(Task(id = 0, title = title, projectId = 7))
        down = false
        return (created as NetworkResult.Success).data.id.also { assertTrue(it < 0L) }
    }

    @Test
    fun `a label added to a task created offline waits for the create and follows it`() = runTest {
        val rig = TaskWriteRig()
        rig.labelDao.upsert(LabelEntity(id = 5, title = "home"))
        val tempId = rig.createOffline("Buy milk")

        val result = rig.labels.addToTask(tempId, 5)

        assertIs<NetworkResult.Success<*>>(result)
        assertTrue(rig.labelRequests.isEmpty(), "nothing is sent for a task the server does not know")
        assertTrue(rig.queued().any { it.actionType == "add_label" && it.payload == "$tempId:5" })
        assertTrue(checkNotNull(rig.taskDao.entity(tempId)).labelsJson.contains("\"id\":5"))

        assertTrue(rig.sync.engine.performSync())
        val realId = rig.server.rows.values.single { it.title == "Buy milk" }.id
        assertEquals(listOf("POST /tasks/$realId/labels"), rig.labelRequests)
        assertTrue(rig.queued().isEmpty())
        rig.sync.close()
    }

    @Test
    fun `a label created offline is put on a task through the queue`() = runTest {
        val rig = TaskWriteRig()

        rig.labels.addToTask(42, -3)

        assertTrue(rig.sent.isEmpty())
        assertEquals("42:-3", rig.queued().single().payload)
    }

    @Test
    fun `a label change sent at once keeps a queued edit on the task row`() = runTest {
        val rig = TaskWriteRig()
        rig.labelDao.upsert(LabelEntity(id = 5, title = "home"))
        // An edit the server refused stays on the row until the user retries or discards it.
        rig.pendingActionDao.insert(
            PendingActionEntity(
                entityType = "task", entityId = 42, actionType = "update", payload = """{"title":"Local"}""",
                status = "failed", createdAt = "2026-10-06T10:00:00Z", updatedAt = "2026-10-06T10:00:00Z",
            ),
        )
        rig.taskDao.upsert(checkNotNull(rig.taskDao.entity(42)).copy(title = "Local"))

        rig.labels.addToTask(42, 5)

        assertEquals(listOf("POST /tasks/42/labels"), rig.labelRequests)
        val row = checkNotNull(rig.taskDao.entity(42))
        assertEquals("Local", row.title, "the server's title did not replace the unsynced one")
        assertTrue(row.labelsJson.contains("\"id\":5"), "the label shows")
    }

    @Test
    fun `a relation does not replace a task row that holds a queued edit`() = runTest {
        val rig = TaskWriteRig()
        rig.server.seed(43, "Other on server", "", done = false, projectId = 7)
        rig.taskDao.upsert(checkNotNull(rig.taskDao.entity(42)).copy(id = 43, title = "Other, edited here"))
        rig.pendingActionDao.insert(
            PendingActionEntity(
                entityType = "task", entityId = 43, actionType = "update", payload = """{"title":"Other, edited here"}""",
                createdAt = "2026-10-06T10:00:00Z", updatedAt = "2026-10-06T10:00:00Z",
            ),
        )

        assertIs<NetworkResult.Success<*>>(rig.tasks.repository.createRelation(42, 43, RelationKind.RELATED))
        assertEquals("Other, edited here", checkNotNull(rig.taskDao.entity(43)).title)

        assertIs<NetworkResult.Success<*>>(rig.tasks.repository.deleteRelation(42, RelationKind.RELATED, 43))
        assertEquals("Other, edited here", checkNotNull(rig.taskDao.entity(43)).title)
    }
}
