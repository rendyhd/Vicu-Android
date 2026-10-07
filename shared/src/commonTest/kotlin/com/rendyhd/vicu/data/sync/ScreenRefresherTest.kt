@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.rendyhd.vicu.data.sync

import com.rendyhd.vicu.ui.FakeLabelRepository
import com.rendyhd.vicu.ui.FakeProjectRepository
import com.rendyhd.vicu.ui.FakeTaskRepository
import com.rendyhd.vicu.util.NetworkResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a list screen's refresh does with the results of the three refreshes it runs: the app is
 * "fresh" only after all of them succeeded, and a failure is reported instead of swallowed.
 */
class ScreenRefresherTest {

    private class Rig {
        val tasks = FakeTaskRepository()
        val projects = FakeProjectRepository()
        val labels = FakeLabelRepository()
        val staleness = SyncStaleness()
        val refresher = ScreenRefresher(tasks, projects, labels, staleness)
    }

    private val offline = NetworkResult.Error("Can't reach the server", offline = true)

    @Test
    fun `a refresh that succeeds in everything marks the app fresh`() = runTest {
        val rig = Rig()
        assertTrue(rig.refresher.isStale())

        val result = rig.refresher.refresh()

        assertEquals(NetworkResult.Success(Unit), result)
        assertFalse(rig.refresher.isStale())
        assertEquals(1, rig.projects.refreshCalls)
        assertEquals(1, rig.labels.refreshCalls)
    }

    @Test
    fun `a failed task refresh is reported and the app stays stale`() = runTest {
        val rig = Rig()
        rig.tasks.refreshResult = NetworkResult.Error("Server error", code = 500)

        val result = rig.refresher.refresh()

        assertEquals(NetworkResult.Error("Server error", code = 500), result)
        assertTrue(rig.refresher.isStale(), "the next screen tries again instead of trusting a failed refresh")
        assertEquals(0, rig.projects.refreshCalls, "the rest is skipped once the server cannot be used")
    }

    @Test
    fun `a failed project or label refresh also leaves the app stale`() = runTest {
        val rig = Rig()
        rig.projects.refreshResult = NetworkResult.Error("Projects broke")
        assertEquals(NetworkResult.Error("Projects broke"), rig.refresher.refresh())
        assertTrue(rig.refresher.isStale())

        rig.projects.refreshResult = NetworkResult.Success(Unit)
        rig.labels.refreshResult = NetworkResult.Error("Labels broke")
        assertEquals(NetworkResult.Error("Labels broke"), rig.refresher.refresh())
        assertTrue(rig.refresher.isStale())
    }

    @Test
    fun `a refresh that was skipped because the app is fresh does no work`() = runTest {
        val rig = Rig()
        rig.refresher.refresh()
        rig.tasks.refreshes.clear()

        val result = rig.refresher.refresh()

        assertEquals(NetworkResult.Success(Unit), result)
        assertTrue(rig.tasks.refreshes.isEmpty())
    }

    @Test
    fun `a user-started refresh always runs and asks for a full reconcile`() = runTest {
        val rig = Rig()
        rig.refresher.refresh()

        rig.refresher.refresh(manual = true)

        assertEquals(listOf(false, true), rig.tasks.fullRefreshes)
    }

    @Test
    fun `a refresh that was waiting behind another does not repeat it`() = runTest {
        val rig = Rig()
        val gate = CompletableDeferred<Unit>()
        rig.tasks.refreshGate = gate
        val first = async { rig.refresher.refresh() }
        runCurrent()
        val second = async { rig.refresher.refresh() }
        runCurrent()

        gate.complete(Unit)
        first.await()
        second.await()

        assertEquals(1, rig.tasks.refreshes.size, "the second screen found the app fresh when its turn came")
    }

    @Test
    fun `a custom task refresh is used and does not mark the app fresh`() = runTest {
        val rig = Rig()
        var custom = 0

        val result = rig.refresher.refresh(manual = false) {
            custom++
            NetworkResult.Success(Unit)
        }

        assertEquals(NetworkResult.Success(Unit), result)
        assertEquals(1, custom)
        assertTrue(rig.tasks.refreshes.isEmpty())
        assertTrue(rig.refresher.isStale(), "a filtered fetch does not bring every task up to date")
        assertEquals(1, rig.projects.refreshCalls)
    }

    @Test
    fun `an offline failure is shown only when the user asked for the refresh`() {
        assertNull(offline.refreshErrorToShow(manual = false))
        assertEquals("Can't reach the server", offline.refreshErrorToShow(manual = true))
    }

    @Test
    fun `any other failure is shown even when nobody asked`() {
        val serverError = NetworkResult.Error("Server error", code = 500)

        assertEquals("Server error", serverError.refreshErrorToShow(manual = false))
        assertNull(NetworkResult.Success(Unit).refreshErrorToShow(manual = true))
    }
}
