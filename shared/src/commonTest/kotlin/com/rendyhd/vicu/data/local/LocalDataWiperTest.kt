package com.rendyhd.vicu.data.local

import com.rendyhd.vicu.domain.model.BottomBarSlot
import com.rendyhd.vicu.worker.SyncEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalDataWiperTest {

    private fun fixture(): WiperFixture = WiperFixture().apply {
        dao.taskIds += listOf(1, 2, 3, -4)
        dao.projectIds += listOf(10, 11)
        dao.labelIds += listOf(20, -21)
        dao.attachmentIds += 30
        dao.pending += queuedAction(entityId = 2)                                  // edit of cached task 2
        dao.pending += queuedAction(entityId = -4, actionType = "create")          // offline-created task
        dao.pending += queuedAction(entityId = -21, entityType = "label", actionType = "create")
        dao.addRoutineHistory("routine-a:2026-01-01", "routine-a:2026-01-02")
    }

    // --- clearCaches ---

    @Test
    fun `clearing caches keeps the offline queue and routine history`() = runTest {
        val f = fixture()

        f.wiper.clearCaches()

        assertEquals(3, f.dao.pending.size)
        assertEquals(2, f.dao.routineArchive.size)
    }

    @Test
    fun `clearing caches keeps the rows queued actions refer to and drops the rest`() = runTest {
        val f = fixture()
        f.dao.pending += queuedAction(entityId = 3, status = "failed")
        f.dao.pending += queuedAction(entityId = 1, status = "completed")

        f.wiper.clearCaches()

        // 2 (pending edit), 3 (failed edit) and -4 (offline create) stay; 1's action is done.
        assertEquals(setOf(2L, 3L, -4L), f.dao.taskIds)
        assertEquals(setOf(-21L), f.dao.labelIds)
        assertTrue(f.dao.projectIds.isEmpty())
        assertTrue(f.dao.attachmentIds.isEmpty())
    }

    @Test
    fun `clearing caches does not touch custom lists, the bottom bar or alarms`() = runTest {
        val f = fixture()
        f.bottomBar.saveSlots(BottomBarSlot.DEFAULT_SLOTS.reversed())

        f.wiper.clearCaches()

        assertEquals(0, f.customLists.clearLocalCalls)
        assertEquals(0, f.hooks.cancelAllAlarmsCalls)
        assertEquals(BottomBarSlot.DEFAULT_SLOTS.reversed(), f.bottomBar.slots.first())
    }

    @Test
    fun `clearing caches makes the next screen refresh`() = runTest {
        val f = fixture()
        f.staleness.markSynced()
        assertFalse(f.staleness.isStale())

        f.wiper.clearCaches()

        assertTrue(f.staleness.isStale())
    }

    @Test
    fun `clearing caches forgets the refresh cursor so the next refresh is a full one`() = runTest {
        val f = fixture()
        f.syncCursor.commit(f.syncCursor.begin(), "2026-10-06T09:00:00Z", 5L)

        f.wiper.clearCaches()

        assertNull(f.syncCursor.begin().cursor.tasksUpdatedSince)
    }

    // --- discardUnsyncedAndClearCaches ---

    @Test
    fun `discarding drops the queue and every cached task but keeps routine history`() = runTest {
        val f = fixture()

        f.wiper.discardUnsyncedAndClearCaches()

        assertTrue(f.dao.pending.isEmpty())
        assertTrue(f.dao.taskIds.isEmpty(), "no ghost rows of discarded creates")
        assertTrue(f.dao.labelIds.isEmpty())
        assertTrue(f.dao.projectIds.isEmpty())
        assertEquals(2, f.dao.routineArchive.size)
    }

    @Test
    fun `discarding and wiping also forget the refresh cursor`() = runTest {
        val f = fixture()
        f.syncCursor.commit(f.syncCursor.begin(), "2026-10-06T09:00:00Z", 5L)
        f.wiper.discardUnsyncedAndClearCaches()
        assertNull(f.syncCursor.begin().cursor.tasksUpdatedSince)

        f.syncCursor.commit(f.syncCursor.begin(), "2026-10-06T09:00:00Z", 5L)
        f.wiper.wipeEverything()
        assertNull(f.syncCursor.begin().cursor.tasksUpdatedSince)
    }

    // --- wipeEverything ---

    @Test
    fun `wiping everything clears every table, local settings and alarms`() = runTest {
        val f = fixture()
        f.bottomBar.saveSlots(BottomBarSlot.DEFAULT_SLOTS.reversed())

        f.wiper.wipeEverything()

        assertTrue(f.dao.taskIds.isEmpty() && f.dao.projectIds.isEmpty() && f.dao.labelIds.isEmpty())
        assertTrue(f.dao.attachmentIds.isEmpty() && f.dao.pending.isEmpty() && f.dao.routineArchive.isEmpty())
        assertEquals(1, f.customLists.clearLocalCalls)
        assertEquals(BottomBarSlot.DEFAULT_SLOTS, f.bottomBar.slots.first())
        assertEquals(1, f.hooks.cancelAllAlarmsCalls)
        assertTrue(f.hooks.widgetUpdates > 0)
        assertEquals(0, f.wiper.routineHistoryCount.first())
    }

    @Test
    fun `wiping everything forgets the preferences that only made sense for the old account`() = runTest {
        val f = fixture()
        f.projectSections.setExpanded(rootProjectId = 10, sectionProjectId = 11, isExpanded = false)
        f.labelOrder.setOrder(listOf(20, 21, 22))
        f.routinePrefs.setRemindersEnabled(false)
        val oldDeviceId = f.routinePrefs.getOrCreateDeviceId()
        f.widgetPrefs.setSmartAdd(false)
        f.widgetPrefs.setContextNav(false)

        f.wiper.wipeEverything()

        assertEquals(emptySet(), f.projectSections.collapsedSectionIds(10).first())
        assertEquals(emptyList(), f.labelOrder.getOrder().first())
        assertTrue(f.routinePrefs.remindersEnabled.first(), "back to the default")
        assertTrue(f.routinePrefs.getOrCreateDeviceId() != oldDeviceId, "a new device id for the next account")
        assertTrue(f.widgetPrefs.smartAdd.first() && f.widgetPrefs.contextNav.first())
    }

    @Test
    fun `wiping everything forgets the remembered carrier ids but a cache clear keeps them`() = runTest {
        val f = fixture()
        f.carrierIds.set("https://v.example", "custom-lists", listOf(900L), 5L)

        f.wiper.clearCaches()
        assertEquals(listOf(900L), f.carrierIds.get("https://v.example", "custom-lists").ids)

        f.wiper.wipeEverything()
        assertEquals(emptyList(), f.carrierIds.get("https://v.example", "custom-lists").ids)
    }

    @Test
    fun `wiping everything forgets what was learned about the old account's lists`() = runTest {
        val f = fixture()
        f.listPositions.anchorAtEndInBackground(projectId = 7, taskId = 1)
        f.listPositions.awaitIdle()
        assertTrue(f.listPositions.hasCached(7), "the positioner remembered the project's list view")

        f.wiper.wipeEverything()

        assertFalse(f.listPositions.hasCached(7))
    }

    @Test
    fun `wiping everything stops the account's background work and resets its widgets`() = runTest {
        val f = fixture()

        f.wiper.wipeEverything()

        assertEquals(1, f.hooks.cancelAccountBackgroundWorkCalls)
        assertEquals(1, f.hooks.clearWidgetConfigurationsCalls)
    }

    @Test
    fun `clearing caches keeps the preferences and the background work`() = runTest {
        val f = fixture()
        f.projectSections.setExpanded(rootProjectId = 10, sectionProjectId = 11, isExpanded = false)
        f.labelOrder.setOrder(listOf(20, 21))
        f.widgetPrefs.setSmartAdd(false)

        f.wiper.clearCaches()
        f.wiper.discardUnsyncedAndClearCaches()

        // The same account is re-synced, so its ids are still valid.
        assertEquals(setOf(11L), f.projectSections.collapsedSectionIds(10).first())
        assertEquals(listOf(20L, 21L), f.labelOrder.getOrder().first())
        assertEquals(false, f.widgetPrefs.smartAdd.first())
        assertEquals(0, f.hooks.cancelAccountBackgroundWorkCalls)
        assertEquals(0, f.hooks.clearWidgetConfigurationsCalls)
    }

    // --- counts ---

    @Test
    fun `unsynced count covers waiting, in flight and failed actions only`() = runTest {
        val f = WiperFixture()
        f.dao.pending += queuedAction(1, status = "pending")
        f.dao.pending += queuedAction(2, status = "processing")
        f.dao.pending += queuedAction(3, status = "failed")
        f.dao.pending += queuedAction(4, status = "completed")

        assertEquals(3, f.wiper.unsyncedActionCount())
    }

    @Test
    fun `routine history count follows the archive`() = runTest {
        val f = fixture()

        assertEquals(2, f.wiper.routineHistoryCount.first())
    }

    // --- interaction with sync ---

    @Test
    fun `a wipe waits for a running sync instead of racing it`() = runTest {
        val f = fixture()
        val syncRunning = CompletableDeferred<Unit>()
        val syncMayFinish = CompletableDeferred<Unit>()

        withContext(Dispatchers.Default) {
            val fakeSync = async {
                SyncEngine.exclusive {
                    syncRunning.complete(Unit)
                    syncMayFinish.await()
                }
            }
            syncRunning.await()

            val wipe = async { f.wiper.wipeEverything() }
            delay(150)
            assertFalse(wipe.isCompleted, "the wipe must wait while a sync run holds the lock")
            assertEquals(3, f.dao.pending.size, "nothing was deleted yet")

            syncMayFinish.complete(Unit)
            fakeSync.await()
            wipe.await()
        }

        assertTrue(f.dao.pending.isEmpty())
    }
}
