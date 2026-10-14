package com.rendyhd.vicu.data.local.dao

import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The folding rules of cross-app semantics 9.6, the same as the desktop queue's. */
class CollabQueueMergeTest {

    private var nextId = 1L

    private fun action(entityType: String, actionType: String, payload: String, status: String = "pending") = PendingActionEntity(
        id = nextId++,
        entityType = entityType,
        entityId = 7L,
        actionType = actionType,
        payload = payload,
        status = status,
        createdAt = "2026-10-10T08:00:00Z",
        updatedAt = "2026-10-10T08:00:00Z",
    )

    private fun assign(actionType: String, userId: Long, status: String = "pending") =
        action(CollabQueue.ASSIGNEE, actionType, CollabQueue.encode(AssigneePayload(userId, "u$userId", "User $userId")), status)

    private fun comment(actionType: String, commentId: Long, html: String = "", status: String = "pending") =
        action(CollabQueue.COMMENT, actionType, CollabQueue.encode(CommentPayload(commentId, html)), status)

    @Test
    fun `assigning and unassigning the same person cancel`() {
        val add = assign(CollabQueue.ADD, 2)
        val merge = resolveCollabQueueMerge(listOf(add), assign(CollabQueue.REMOVE, 2))
        assertEquals(listOf(add.id), merge.deleteIds)
        assertFalse(merge.insert)
    }

    @Test
    fun `a repeated assignment is not queued twice`() {
        val merge = resolveCollabQueueMerge(listOf(assign(CollabQueue.ADD, 2)), assign(CollabQueue.ADD, 2))
        assertTrue(merge.deleteIds.isEmpty())
        assertFalse(merge.insert)
    }

    @Test
    fun `changes for other people or already being sent stay`() {
        val other = assign(CollabQueue.ADD, 3)
        val sending = assign(CollabQueue.ADD, 2, status = "in_progress")
        val merge = resolveCollabQueueMerge(listOf(other, sending), assign(CollabQueue.REMOVE, 2))
        assertTrue(merge.deleteIds.isEmpty())
        assertTrue(merge.insert)
    }

    @Test
    fun `an edit of a comment waiting to be created goes into the create`() {
        val create = comment(CollabQueue.CREATE, -5, "<p>first</p>")
        val merge = resolveCollabQueueMerge(listOf(create), comment(CollabQueue.UPDATE, -5, "<p>second</p>"))
        assertFalse(merge.insert)
        assertEquals("<p>second</p>", CollabQueue.comment(merge.rewrites.getValue(create.id))?.comment)
        assertEquals(-5, CollabQueue.comment(merge.rewrites.getValue(create.id))?.commentId)
    }

    @Test
    fun `two edits keep the last text`() {
        val edit = comment(CollabQueue.UPDATE, 9, "<p>a</p>")
        val next = comment(CollabQueue.UPDATE, 9, "<p>b</p>")
        val merge = resolveCollabQueueMerge(listOf(edit), next)
        assertFalse(merge.insert)
        assertEquals(next.payload, merge.rewrites[edit.id])
    }

    @Test
    fun `deleting a comment that was never sent removes its create and edits`() {
        val create = comment(CollabQueue.CREATE, -5, "<p>x</p>")
        val merge = resolveCollabQueueMerge(listOf(create), comment(CollabQueue.DELETE, -5))
        assertEquals(listOf(create.id), merge.deleteIds)
        assertFalse(merge.insert)
    }

    @Test
    fun `a delete after an edit replaces the edit`() {
        val edit = comment(CollabQueue.UPDATE, 9, "<p>a</p>")
        val merge = resolveCollabQueueMerge(listOf(edit), comment(CollabQueue.DELETE, 9))
        assertEquals(listOf(edit.id), merge.deleteIds)
        assertTrue(merge.insert)
    }

    @Test
    fun `payloads survive a round trip`() {
        val payload = CommentPayload(-3, "<p>hi</p>", maybeSent = true)
        assertEquals(payload, CollabQueue.comment(CollabQueue.encode(payload)))
        assertEquals(AssigneePayload(4, "maya", "Maya"), CollabQueue.assignee(CollabQueue.encode(AssigneePayload(4, "maya", "Maya"))))
    }
}
