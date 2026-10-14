package com.rendyhd.vicu.worker

import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.WorkInfo
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** When a new reason to sync replaces the queued "when online" sync, and when it leaves it alone. */
class SyncSchedulerTest {

    private val enqueued = WorkInfo.State.ENQUEUED
    private val running = WorkInfo.State.RUNNING
    private val blocked = WorkInfo.State.BLOCKED

    @Test
    fun `a sync that waits out the backoff of a failed run is replaced`() {
        assertEquals(ExistingWorkPolicy.REPLACE, SyncScheduler.policyFor(listOf(enqueued to 1)))
        assertEquals(ExistingWorkPolicy.REPLACE, SyncScheduler.policyFor(listOf(enqueued to 6)))
        // A failed run with a follow-up behind it: the whole line starts again now.
        assertEquals(ExistingWorkPolicy.REPLACE, SyncScheduler.policyFor(listOf(enqueued to 2, blocked to 0)))
    }

    @Test
    fun `a sync that only waits for the network is kept, and so is a new one`() {
        assertEquals(ExistingWorkPolicy.KEEP, SyncScheduler.policyFor(listOf(enqueued to 0)))
        assertEquals(ExistingWorkPolicy.KEEP, SyncScheduler.policyFor(emptyList()))
    }

    @Test
    fun `one more sync is lined up behind a running one, and only one`() {
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, SyncScheduler.policyFor(listOf(running to 0)))
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, SyncScheduler.policyFor(listOf(running to 3)))
        assertEquals(ExistingWorkPolicy.KEEP, SyncScheduler.policyFor(listOf(running to 0, blocked to 0)))
    }

    @Test
    fun `the queued sync waits for a network`() {
        val spec = SyncScheduler.whenOnlineRequest().workSpec

        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(30_000L, spec.backoffDelayDuration)
    }

    @Test
    fun `reconnections are the moments the network comes back`() = runTest {
        assertEquals(2, flowOf(true, false, false, true, true, false, true).reconnections().toList().size)
        assertEquals(1, flowOf(false, true).reconnections().toList().size)
        assertEquals("being online from the start is not a reconnection", 0, flowOf(true, true).reconnections().toList().size)
        assertEquals(0, flowOf(false).reconnections().toList().size)
    }
}
