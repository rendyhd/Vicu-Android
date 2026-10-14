package com.rendyhd.vicu.worker

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the periodic background sync asks WorkManager for (the request is built without a Context). */
class PeriodicSyncSchedulerTest {

    @Test
    fun `the periodic sync repeats every 30 minutes`() {
        val spec = PeriodicSyncScheduler.request().workSpec

        assertTrue(spec.isPeriodic)
        assertEquals(30 * 60_000L, spec.intervalDuration)
    }

    @Test
    fun `the periodic sync waits for a network`() {
        val spec = PeriodicSyncScheduler.request().workSpec

        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
    }

    @Test
    fun `it is one unique schedule that keeps the one already queued`() {
        assertEquals("periodic_sync", PeriodicSyncScheduler.WORK_NAME)
        assertEquals(ExistingPeriodicWorkPolicy.KEEP, PeriodicSyncScheduler.POLICY)
    }
}
