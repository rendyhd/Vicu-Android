package com.rendyhd.vicu.data.local.dao

import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueMergeTest {

    private fun action(id: Long, type: String, payload: String = "{}") = PendingActionEntity(
        id = id,
        entityType = "task",
        entityId = -42L,
        actionType = type,
        payload = payload,
        createdAt = "2026-06-11T10:00:00Z",
        updatedAt = "2026-06-11T10:00:00Z",
    )

    @Test
    fun `no pending create - replace rows for entity`() {
        val op = resolveTaskQueueMerge(listOf(action(1, "update")), "toggle_done", "{new}")
        assertEquals(QueueMergeOp.ReplaceForEntity, op)
    }

    @Test
    fun `empty queue - replace (plain insert path)`() {
        val op = resolveTaskQueueMerge(emptyList(), "update", "{new}")
        assertEquals(QueueMergeOp.ReplaceForEntity, op)
    }

    @Test
    fun `pending create plus update - fold payload into the create`() {
        val op = resolveTaskQueueMerge(listOf(action(7, "create", "{old}")), "update", "{new}")
        assertEquals(QueueMergeOp.UpdateCreatePayload(7, "{new}"), op)
    }

    @Test
    fun `pending create plus toggle_done - fold payload into the create`() {
        val op = resolveTaskQueueMerge(listOf(action(7, "create", "{old}")), "toggle_done", "{done}")
        assertEquals(QueueMergeOp.UpdateCreatePayload(7, "{done}"), op)
    }

    @Test
    fun `pending create plus delete - drop everything`() {
        val op = resolveTaskQueueMerge(listOf(action(7, "create", "{old}")), "delete", "")
        assertTrue(op is QueueMergeOp.DropAll)
    }
}
