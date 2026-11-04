package com.rendyhd.vicu.data.sync

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.rendyhd.vicu.data.local.FakeCollaborationDao
import com.rendyhd.vicu.data.local.SyncCursorStore
import com.rendyhd.vicu.data.local.entity.TaskSignalEntity
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The comment counts come with the task reads (cross-app semantics 9.4), so a private project's
 * task shows its comments without a request of its own.
 */
class TaskRefresherSignalsTest {

    private fun harness(configure: TaskListServer.() -> Unit = {}): RefresherHarness {
        val server = TaskListServer().apply {
            put(1, "A", updated = "2026-10-06T08:00:00Z").commentCount = 2
            put(2, "B", updated = "2026-10-06T09:00:00Z")
            configure()
        }
        return RefresherHarness(server, collaborationDao = FakeCollaborationDao())
    }

    private val RefresherHarness.signals get() = checkNotNull(collaborationDao).signals.value

    @Test
    fun `a refresh keeps the counts its read brought and writes no row for a task without comments`() = runTest {
        val h = harness()

        h.refresher.refresh()

        assertEquals(TaskSignalEntity(1, 2, false), h.signals[1L])
        assertNull(h.signals[2L], "no comments and no row to correct")
        assertTrue(
            h.server.lists().any { "comment_count" in it.params["expand"].orEmpty() && "is_unread" in it.params["expand"].orEmpty() },
            "${h.server.lists()}",
        )
    }

    @Test
    fun `the delta brings a new count`() = runTest {
        val h = harness()
        h.refresher.refresh()
        h.server.put(2, "B", updated = "2026-10-06T10:00:00Z").apply { commentCount = 1; isUnread = true }

        val outcome = h.refresher.refresh()

        assertFalse(outcome.full)
        assertEquals(TaskSignalEntity(2, 1, true), h.signals[2L])
    }

    @Test
    fun `the last comment going away overwrites the row`() = runTest {
        val h = harness()
        h.refresher.refresh()
        h.server.put(1, "A", updated = "2026-10-06T10:00:00Z")

        h.refresher.refresh()

        assertEquals(TaskSignalEntity(1, 0, false), h.signals[1L])
    }

    @Test
    fun `a task with changes queued keeps its count`() = runTest {
        val h = harness()
        h.refresher.refresh()
        h.queueTaskAction(1)
        h.server.put(1, "A", updated = "2026-10-06T10:00:00Z").commentCount = 5

        h.refresher.refresh()

        assertEquals(2, h.signals.getValue(1L).commentCount, "the count may hold a comment not sent yet")
    }

    @Test
    fun `an install that read before the counts came with the tasks reads everything once`() = runTest {
        // A cursor from before this version: recent, so nothing else would ask for a full read.
        val stored = InMemoryPreferencesDataStore()
        stored.edit {
            it[stringPreferencesKey("tasks_updated_since")] = "2026-10-06T09:00:00Z"
            it[longPreferencesKey("last_full_reconcile_ms")] = Instant.parse("2026-10-06T11:00:00Z").toEpochMilliseconds()
        }
        val h = RefresherHarness(
            server = TaskListServer().apply { put(1, "A", updated = "2026-10-06T08:00:00Z").commentCount = 2 },
            cursorStore = SyncCursorStore(stored),
            collaborationDao = FakeCollaborationDao(),
        )

        val first = h.refresher.refresh()
        val second = h.refresher.refresh()

        assertTrue(first.full, "the counts of tasks that did not change are read once")
        assertEquals(TaskSignalEntity(1, 2, false), h.signals[1L])
        assertTrue(h.cursorStore.begin().cursor.signalsRead)
        assertFalse(second.full)
    }
}
