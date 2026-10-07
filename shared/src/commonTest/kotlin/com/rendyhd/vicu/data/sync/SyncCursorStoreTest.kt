package com.rendyhd.vicu.data.sync

import com.rendyhd.vicu.data.local.SyncCursorStore
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncCursorStoreTest {

    @Test
    fun `a new store has no cursor and no full reconcile on record`() = runTest {
        val cursor = SyncCursorStore(InMemoryPreferencesDataStore()).begin().cursor

        assertNull(cursor.tasksUpdatedSince)
        assertEquals(0L, cursor.lastFullReconcileMs)
        assertFalse(cursor.fullReconcileRequested)
    }

    @Test
    fun `a commit is stored and a later commit without a cursor keeps the old one`() = runTest {
        val store = SyncCursorStore(InMemoryPreferencesDataStore())
        assertTrue(store.commit(store.begin(), "2026-10-06T09:00:00Z", fullReconcileAtMs = 123L))

        assertTrue(store.commit(store.begin(), tasksUpdatedSince = null))

        val cursor = store.begin().cursor
        assertEquals("2026-10-06T09:00:00Z", cursor.tasksUpdatedSince)
        assertEquals(123L, cursor.lastFullReconcileMs)
    }

    @Test
    fun `the cursor survives a new store over the same file`() = runTest {
        val file = InMemoryPreferencesDataStore()
        val first = SyncCursorStore(file)
        first.commit(first.begin(), "2026-10-06T09:00:00Z", 5L)

        assertEquals("2026-10-06T09:00:00Z", SyncCursorStore(file).begin().cursor.tasksUpdatedSince)
    }

    @Test
    fun `clearing forgets everything and refuses a commit from a refresh that was already running`() = runTest {
        val store = SyncCursorStore(InMemoryPreferencesDataStore())
        store.commit(store.begin(), "2026-10-06T09:00:00Z", 5L)
        val running = store.begin()

        store.clear()

        assertFalse(store.commit(running, "2026-10-06T10:00:00Z", 6L))
        assertNull(store.begin().cursor.tasksUpdatedSince)
        assertEquals(0L, store.begin().cursor.lastFullReconcileMs)
    }

    @Test
    fun `a requested reconcile is kept until a full reconcile commits`() = runTest {
        val store = SyncCursorStore(InMemoryPreferencesDataStore())
        store.commit(store.begin(), "2026-10-06T09:00:00Z", 5L)

        store.requestFullReconcile()
        assertTrue(store.begin().cursor.fullReconcileRequested)

        // An incremental refresh that was running cannot store its result over the request.
        val staleTicket = store.begin()
        store.requestFullReconcile()
        assertFalse(store.commit(staleTicket, "2026-10-06T11:00:00Z"))
        assertTrue(store.begin().cursor.fullReconcileRequested)

        assertTrue(store.commit(store.begin(), "2026-10-06T12:00:00Z", fullReconcileAtMs = 9L))
        assertFalse(store.begin().cursor.fullReconcileRequested)
    }
}
