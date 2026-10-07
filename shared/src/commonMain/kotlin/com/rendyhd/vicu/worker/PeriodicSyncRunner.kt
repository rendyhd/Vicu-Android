package com.rendyhd.vicu.worker

import com.rendyhd.vicu.util.Logger
import kotlin.coroutines.cancellation.CancellationException

/**
 * One run of the periodic background sync: bring the local data up to date with the server, then
 * make the reminder alarms and the widgets show it.
 *
 * The alarm and widget steps work from local data, so they run even when the server could not be
 * reached (an alarm lost to a reboot or a changed time zone is still repaired), and a failure in
 * one step never stops the next.
 */
class PeriodicSyncRunner(
    /** False when nobody is signed in: nothing runs, and nothing is shown for a gone account. */
    private val isSignedIn: suspend () -> Boolean,
    /** Syncs with the server; true when everything went through, false when a retry is due. */
    private val sync: suspend () -> Boolean,
    private val rescheduleAlarms: suspend () -> Unit,
    private val updateWidgets: () -> Unit,
) {
    enum class Outcome { SYNCED, SYNC_INCOMPLETE, SKIPPED }

    suspend fun run(): Outcome {
        if (!isSignedIn()) return Outcome.SKIPPED

        val synced = try {
            sync()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Periodic sync failed: ${e.message}")
            false
        }
        try {
            rescheduleAlarms()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Rescheduling alarms after the periodic sync failed: ${e.message}")
        }
        try {
            updateWidgets()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Updating widgets after the periodic sync failed: ${e.message}")
        }
        return if (synced) Outcome.SYNCED else Outcome.SYNC_INCOMPLETE
    }

    private companion object {
        const val TAG = "PeriodicSync"
    }
}
