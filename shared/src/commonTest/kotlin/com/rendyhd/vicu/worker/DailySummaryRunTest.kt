package com.rendyhd.vicu.worker

import com.rendyhd.vicu.worker.DailySummaryRun.Outcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class DailySummaryRunTest {

    private class Rig(
        var signedIn: Boolean = true,
        var sync: suspend () -> Unit = {},
        var summarize: suspend () -> Unit = {},
        var scheduleNext: suspend () -> Unit = {},
    ) {
        val steps = mutableListOf<String>()

        val run = DailySummaryRun(
            isSignedIn = { signedIn },
            syncAttempt = {
                steps += "sync"
                sync()
            },
            syncTimeout = 30.seconds,
            summarize = {
                steps += "summary"
                summarize()
            },
            scheduleNext = {
                steps += "next"
                scheduleNext()
            },
        )
    }

    @Test
    fun `a run syncs first, then posts the summary, then schedules the next one`() = runTest {
        val rig = Rig()

        assertEquals(Outcome.DONE, rig.run.run())

        assertEquals(listOf("sync", "summary", "next"), rig.steps)
    }

    @Test
    fun `a failed sync does not stop the summary from being posted from local data`() = runTest {
        val rig = Rig(sync = { error("offline") })

        assertEquals(Outcome.DONE, rig.run.run())

        assertEquals(listOf("sync", "summary", "next"), rig.steps)
    }

    @Test
    fun `a sync that takes too long is given up on after its time box`() = runTest {
        val rig = Rig(sync = { delay(10.minutes) })

        rig.run.run()

        assertEquals(listOf("sync", "summary", "next"), rig.steps)
        assertEquals(30_000L, currentTime, "the summary waited for the time box and no longer")
    }

    @Test
    fun `a summary that fails still schedules the next occurrence`() = runTest {
        val rig = Rig(summarize = { error("database") })

        assertEquals(Outcome.DONE, rig.run.run())

        assertEquals(listOf("sync", "summary", "next"), rig.steps)
    }

    @Test
    fun `a failure to schedule the next occurrence does not fail the run`() = runTest {
        val rig = Rig(scheduleNext = { error("work manager") })

        assertEquals(Outcome.DONE, rig.run.run())
    }

    @Test
    fun `cancelling the work midway still queues the next day's summary`() = runTest {
        val rig = Rig(summarize = { throw CancellationException("stopped") })

        assertFailsWith<CancellationException> { rig.run.run() }

        assertEquals(listOf("sync", "summary", "next"), rig.steps)
    }

    @Test
    fun `when nobody is signed in nothing runs and nothing is scheduled again`() = runTest {
        val rig = Rig(signedIn = false)

        assertEquals(Outcome.SKIPPED, rig.run.run())

        assertEquals(emptyList(), rig.steps)
    }
}
