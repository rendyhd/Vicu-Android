package com.rendyhd.vicu.notification

import androidx.work.ExistingWorkPolicy
import com.rendyhd.vicu.data.local.NotificationPrefs
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the daily summary scheduler asks WorkManager for (built without a Context). */
class DailySummarySchedulerTest {

    @Test
    fun `both summaries follow the preferences`() {
        val prefs = NotificationPrefs(
            dailySummaryEnabled = true, dailySummaryHour = 7, dailySummaryMinute = 30,
            afternoonSummaryEnabled = false, afternoonSummaryHour = 17, afternoonSummaryMinute = 5,
        )

        val plans = DailySummaryScheduler.slotPlans(prefs)

        assertEquals(
            listOf(
                SlotPlan(DailySummaryScheduler.SLOT_MORNING, enabled = true, hour = 7, minute = 30),
                SlotPlan(DailySummaryScheduler.SLOT_AFTERNOON, enabled = false, hour = 17, minute = 5),
            ),
            plans,
        )
    }

    @Test
    fun `starting the app leaves a summary that is already queued or running alone`() {
        // Replacing it at every start would cancel a summary that is being posted right then.
        assertEquals(ExistingWorkPolicy.KEEP, DailySummaryScheduler.START_POLICY)
    }

    @Test
    fun `the job waits until the target and carries its slot and target`() {
        val now = Instant.parse("2026-10-07T06:00:00Z")
        val target = Instant.parse("2026-10-07T08:30:00Z")

        val spec = DailySummaryScheduler.request(DailySummaryScheduler.SLOT_AFTERNOON, target, now).workSpec

        assertEquals(150 * 60_000L, spec.initialDelay)
        assertEquals(DailySummaryScheduler.SLOT_AFTERNOON, spec.input.getString(DailySummaryScheduler.KEY_SLOT))
        assertEquals(target.toEpochMilliseconds(), spec.input.getLong(DailySummaryScheduler.KEY_TARGET_MILLIS, 0L))
        assertFalse(spec.isPeriodic)
    }

    @Test
    fun `a target that has passed runs at once`() {
        val now = Instant.parse("2026-10-07T09:00:00Z")
        val target = Instant.parse("2026-10-07T08:30:00Z")

        val spec = DailySummaryScheduler.request(DailySummaryScheduler.SLOT_MORNING, target, now).workSpec

        assertEquals(0L, spec.initialDelay)
        assertTrue(spec.input.getString(DailySummaryScheduler.KEY_SLOT) == DailySummaryScheduler.SLOT_MORNING)
    }
}
