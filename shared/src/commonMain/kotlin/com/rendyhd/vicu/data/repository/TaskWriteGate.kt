package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.local.dao.PendingActionDao
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.isRetriableNetworkError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The one place where a change to an existing task decides between "send it now" and "queue it".
 * Shared by the task and label repositories (edits, completions, deletes, labels on a task).
 *
 * The queue replays each action as it was written. If a newer change were sent straight to the
 * server while an older one for the same task still waits in the queue, the replay would later
 * send the older one on top of it and the user's last change would be lost. So:
 *
 *  - while the queue holds a change for the task that has not been sent yet (pending or being
 *    sent; failed ones wait for the user and do not count), a new change joins the queue, where
 *    it is merged with what waits;
 *  - changes to one task run one at a time, so a request still in flight cannot be overtaken by
 *    the next change and then be queued behind it when it fails;
 *  - a failure worth retrying (offline, timeout, 5xx, 429) queues the change; one the server
 *    refuses is handed back;
 *  - cancelled while waiting or in flight (the screen was left), the change is queued: whether it
 *    reached the server is unknown, and a merge patch, a completion, a delete or a label change
 *    can safely be sent twice.
 *
 * Writes to different tasks do not wait for each other. The sync engine's replay is the queue's
 * own sender and does not come through here.
 */
class TaskWriteGate(
    private val pendingActionDao: PendingActionDao,
) {
    private class Lock {
        val mutex = Mutex()
        var users = 0
    }

    private val guard = Mutex()
    private val locks = HashMap<Long, Lock>()

    /**
     * Makes one change to the existing task [taskId] (> 0): [send] sends it and stores the answer,
     * [queue] stores it locally and queues it, [refused] handles a failure that is not worth
     * retrying.
     */
    suspend fun <T> sendOrQueue(
        taskId: Long,
        send: suspend () -> NetworkResult<T>,
        queue: suspend () -> NetworkResult<T>,
        refused: suspend (Exception) -> NetworkResult<T>,
    ): NetworkResult<T> {
        var queued = false
        try {
            return withTaskLock(taskId) {
                if (pendingActionDao.hasWaitingForTask(taskId)) {
                    queue().also { queued = true }
                } else {
                    try {
                        send()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (isRetriableNetworkError(e)) queue().also { queued = true } else refused(e)
                    }
                }
            }
        } catch (e: CancellationException) {
            if (!queued) withContext(NonCancellable) { queue() }
            throw e
        }
    }

    /** Runs [block] while no other write to task [taskId] runs. Not re-entrant. */
    suspend fun <T> withTaskLock(taskId: Long, block: suspend () -> T): T {
        val lock = guard.withLock { locks.getOrPut(taskId) { Lock() }.also { it.users++ } }
        try {
            return lock.mutex.withLock { block() }
        } finally {
            // Also when cancelled, or the entry would stay in the map.
            withContext(NonCancellable) {
                guard.withLock {
                    lock.users--
                    if (lock.users == 0) locks.remove(taskId)
                }
            }
        }
    }

    /** Writes to task [taskId] running or waiting for their turn. For tests. */
    suspend fun writersFor(taskId: Long): Int = guard.withLock { locks[taskId]?.users ?: 0 }
}
