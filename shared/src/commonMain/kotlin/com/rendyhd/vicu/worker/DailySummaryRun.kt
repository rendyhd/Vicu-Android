package com.rendyhd.vicu.worker

import com.rendyhd.vicu.util.Logger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * One run of a daily summary: a sync attempt first, so the numbers are current, then the summary
 * itself, and always the next occurrence, so a failure today does not end the daily chain.
 *
 * The sync is time-boxed and its failure is not the summary's: offline, the summary reports what
 * the device has.
 */
class DailySummaryRun(
    /** False when nobody is signed in: nothing is shown and nothing is scheduled again. */
    private val isSignedIn: suspend () -> Boolean,
    private val syncAttempt: suspend () -> Unit,
    private val syncTimeout: Duration,
    private val summarize: suspend () -> Unit,
    private val scheduleNext: suspend () -> Unit,
) {
    enum class Outcome { DONE, SKIPPED }

    suspend fun run(): Outcome {
        if (!isSignedIn()) return Outcome.SKIPPED
        try {
            try {
                withTimeoutOrNull(syncTimeout) { syncAttempt() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "Sync before the summary failed: ${e.message}")
            }
            try {
                summarize()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "Daily summary failed: ${e.message}")
            }
        } finally {
            // Also when the work is cancelled midway: the next day's summary must still be queued.
            withContext(NonCancellable) {
                try {
                    scheduleNext()
                } catch (e: Exception) {
                    Logger.e(TAG, "Could not schedule the next daily summary", e)
                }
            }
        }
        return Outcome.DONE
    }

    private companion object {
        const val TAG = "DailySummaryRun"
    }
}
