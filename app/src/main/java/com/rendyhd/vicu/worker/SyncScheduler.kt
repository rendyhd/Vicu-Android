package com.rendyhd.vicu.worker

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object SyncScheduler {

    private const val TAG = "SyncScheduler"
    private const val WORK_NAME_ONLINE = "sync_when_online"
    private const val WORK_NAME_IMMEDIATE = "sync_immediate"

    /** One decision at a time, off the caller's thread: reading the state of the work blocks. */
    private val decisions = Executors.newSingleThreadExecutor()

    /**
     * Asks for a sync as soon as there is a network. Call it when something happened that is worth
     * a run: a change was queued, the app came to the foreground, the network came back.
     *
     * A sync that waits for the network is kept. One that waits out the backoff after a failed
     * run is replaced, so it starts as soon as the network allows instead of minutes or hours later:
     * WorkManager would otherwise keep the old request and its delay and ignore this one. While a
     * sync runs, one more is lined up after it (once), for a change queued too late for the running
     * one to send. Retries with nothing new happening keep their backoff, so a server that stays
     * down is not asked in a tight loop.
     */
    fun enqueueWhenOnline(context: Context) {
        val appContext = context.applicationContext
        decisions.execute {
            try {
                val manager = WorkManager.getInstance(appContext)
                val unfinished = manager.getWorkInfosForUniqueWork(WORK_NAME_ONLINE).get()
                    .filterNot { it.state.isFinished }
                    .map { it.state to it.runAttemptCount }
                val policy = policyFor(unfinished)
                manager.enqueueUniqueWork(WORK_NAME_ONLINE, policy, whenOnlineRequest()).result.get()
            } catch (e: Exception) {
                Log.w(TAG, "Could not schedule a sync", e)
            }
        }
    }

    /**
     * What a new request does to the unique "when online" sync: [unfinished] holds the state and
     * run attempt count of each of its works that has not finished (none, one, or a running one
     * with a follow-up).
     */
    internal fun policyFor(unfinished: List<Pair<WorkInfo.State, Int>>): ExistingWorkPolicy = when {
        // Waiting out the backoff of a failed run: start again now.
        unfinished.any { (state, attempts) -> state == WorkInfo.State.ENQUEUED && attempts > 0 } ->
            ExistingWorkPolicy.REPLACE
        // A follow-up is already lined up behind a running sync.
        unfinished.any { (state, _) -> state == WorkInfo.State.BLOCKED } -> ExistingWorkPolicy.KEEP
        // Running: it may have read the queue already; line up one more run after it.
        unfinished.any { (state, _) -> state == WorkInfo.State.RUNNING } -> ExistingWorkPolicy.APPEND_OR_REPLACE
        // Nothing yet, or waiting for the network: it sends everything once it runs.
        else -> ExistingWorkPolicy.KEEP
    }

    internal fun whenOnlineRequest(): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

    fun enqueueImmediate(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME_IMMEDIATE,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(WORK_NAME_ONLINE)
            cancelUniqueWork(WORK_NAME_IMMEDIATE)
        }
    }
}

/**
 * Emits once each time the device goes from offline to online, not for the state it starts in.
 * A sync that failed while offline waits out its backoff even when the network is back; this is
 * the moment to start it again.
 */
fun Flow<Boolean>.reconnections(): Flow<Unit> =
    runningFold(Pair<Boolean?, Boolean?>(null, null)) { (_, last), online -> last to online }
        .filter { (before, now) -> before == false && now == true }
        .map { }
