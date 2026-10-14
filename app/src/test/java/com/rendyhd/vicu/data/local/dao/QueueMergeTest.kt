package com.rendyhd.vicu.data.local.dao

import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.domain.model.Task
import kotlinx.serialization.json.Json
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

    @Test
    fun `a create that is being sent takes no more changes - they queue behind it`() {
        val sending = action(7, "create", "{old}").copy(status = "processing")
        assertEquals(QueueMergeOp.QueueBehindCreate(7), resolveTaskQueueMerge(listOf(sending), "update", "{new}"))
        assertEquals(QueueMergeOp.QueueBehindCreate(7), resolveTaskQueueMerge(listOf(sending), "toggle_done", "{done}"))
        assertEquals(QueueMergeOp.QueueBehindCreate(7), resolveTaskQueueMerge(listOf(sending), "delete", ""))
    }

    @Test
    fun `merge patch payloads keep prior fields and newest explicit values`() {
        val merged = mergePatchPayloads(
            """{"description":"offline edit","done":true,"priority":3}""",
            """{"done":false,"priority":0,"due_date":null}""",
        )

        assertEquals(
            """{"description":"offline edit","done":false,"priority":0,"due_date":null}""",
            merged,
        )
    }

    @Test
    fun `legacy full task queue payload normalizes to writable v2 patch`() {
        val payload = Json.encodeToString(
            Task.serializer(),
            Task(
                id = 42,
                title = "Legacy",
                description = "queued",
                priority = 3,
                dueDate = "2026-08-01T10:00:00Z",
                created = "2026-07-24T10:00:00Z",
                updated = "2026-07-24T11:00:00Z",
                position = 65_536.0,
            ),
        )

        val normalized = normalizeQueuedPatchPayload("task", payload)

        assertTrue("\"due_date\"" in normalized)
        assertTrue("\"priority\":3" in normalized)
        assertTrue("\"id\"" !in normalized)
        assertTrue("\"created\"" !in normalized)
        assertTrue("\"updated\"" !in normalized)
        assertTrue("\"position\"" !in normalized)
    }
}
