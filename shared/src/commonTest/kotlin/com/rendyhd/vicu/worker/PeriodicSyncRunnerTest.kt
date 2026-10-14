package com.rendyhd.vicu.worker

import com.rendyhd.vicu.worker.PeriodicSyncRunner.Outcome
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PeriodicSyncRunnerTest {

    private class Rig(
        var signedIn: Boolean = true,
        var sync: suspend () -> Boolean = { true },
        var alarms: suspend () -> Unit = {},
        var widgets: () -> Unit = {},
    ) {
        val steps = mutableListOf<String>()

        val runner = PeriodicSyncRunner(
            isSignedIn = { signedIn },
            sync = {
                steps += "sync"
                sync()
            },
            rescheduleAlarms = {
                steps += "alarms"
                alarms()
            },
            updateWidgets = {
                steps += "widgets"
                widgets()
            },
        )
    }

    @Test
    fun `a run syncs first, then reschedules alarms, then updates the widgets`() = runTest {
        val rig = Rig()

        val outcome = rig.runner.run()

        assertEquals(Outcome.SYNCED, outcome)
        assertEquals(listOf("sync", "alarms", "widgets"), rig.steps)
    }

    @Test
    fun `a sync that needs a retry still repairs alarms and widgets from local data`() = runTest {
        val rig = Rig(sync = { false })

        val outcome = rig.runner.run()

        assertEquals(Outcome.SYNC_INCOMPLETE, outcome)
        assertEquals(listOf("sync", "alarms", "widgets"), rig.steps)
    }

    @Test
    fun `a sync that throws does not stop the alarms and widgets`() = runTest {
        val rig = Rig(sync = { error("server unreachable") })

        val outcome = rig.runner.run()

        assertEquals(Outcome.SYNC_INCOMPLETE, outcome)
        assertEquals(listOf("sync", "alarms", "widgets"), rig.steps)
    }

    @Test
    fun `a failure while rescheduling alarms does not stop the widget update`() = runTest {
        val rig = Rig(alarms = { error("alarm manager") })

        val outcome = rig.runner.run()

        assertEquals(Outcome.SYNCED, outcome, "the sync itself went through")
        assertEquals(listOf("sync", "alarms", "widgets"), rig.steps)
    }

    @Test
    fun `a failing widget update is not an error of the run`() = runTest {
        val rig = Rig(widgets = { error("glance") })

        assertEquals(Outcome.SYNCED, rig.runner.run())
    }

    @Test
    fun `cancelling the work stops the run instead of being swallowed`() = runTest {
        val rig = Rig(sync = { throw CancellationException("work cancelled") })

        assertFailsWith<CancellationException> { rig.runner.run() }
        assertEquals(listOf("sync"), rig.steps, "nothing after a cancelled sync")
    }

    @Test
    fun `nothing runs when nobody is signed in`() = runTest {
        val rig = Rig(signedIn = false)

        val outcome = rig.runner.run()

        assertEquals(Outcome.SKIPPED, outcome)
        assertEquals(emptyList(), rig.steps)
    }
}
