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
 *
 * Project and label edits follow the same rules through [sendOrQueueEntity], with a lock per
 * project or label: a queued change of one is replayed as it was written too, so a newer edit
 * sent past it would be undone by the replay (review C-8).
 */
class TaskWriteGate(
    private val pendingActionDao: PendingActionDao,
) {
    /** One mutex per key while someone uses it; the entry goes when the last user leaves. */
    private class KeyedLocks<K> {
        private class Lock {
            val mutex = Mutex()
            var users = 0
        }

        private val guard = Mutex()
        private val locks = HashMap<K, Lock>()

        suspend fun <T> withLock(key: K, block: suspend () -> T): T {
            val lock = guard.withLock { locks.getOrPut(key) { Lock() }.also { it.users++ } }
            try {
                return lock.mutex.withLock { block() }
            } finally {
                // Also when cancelled, or the entry would stay in the map.
                withContext(NonCancellable) {
                    guard.withLock {
                        lock.users--
                        if (lock.users == 0) locks.remove(key)
                    }
                }
            }
        }

        suspend fun users(key: K): Int = guard.withLock { locks[key]?.users ?: 0 }
    }

    private val taskLocks = KeyedLocks<Long>()
    private val entityLocks = KeyedLocks<String>()

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
    ): NetworkResult<T> = gated(
        lock = { block -> withTaskLock(taskId, block) },
        waiting = { pendingActionDao.hasWaitingForTask(taskId) },
        send = send,
        queue = queue,
        refused = refused,
    )

    /**
     * [sendOrQueue] for an existing project or label ([entityType] as queued, `project` or
     * `label`, and its server id): while a change of it waits in the queue or is being sent, a new
     * one joins the queue, where it is merged with what waits.
     */
    suspend fun <T> sendOrQueueEntity(
        entityType: String,
        entityId: Long,
        send: suspend () -> NetworkResult<T>,
        queue: suspend () -> NetworkResult<T>,
        refused: suspend (Exception) -> NetworkResult<T>,
    ): NetworkResult<T> = gated(
        lock = { block -> entityLocks.withLock("$entityType:$entityId", block) },
        waiting = {
            pendingActionDao.getActiveByEntity(entityType, entityId)
                .any { it.status == "pending" || it.status == "processing" }
        },
        send = send,
        queue = queue,
        refused = refused,
    )

    private suspend fun <T> gated(
        lock: suspend (suspend () -> NetworkResult<T>) -> NetworkResult<T>,
        waiting: suspend () -> Boolean,
        send: suspend () -> NetworkResult<T>,
        queue: suspend () -> NetworkResult<T>,
        refused: suspend (Exception) -> NetworkResult<T>,
    ): NetworkResult<T> {
        var queued = false
        try {
            return lock {
                if (waiting()) {
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
    suspend fun <T> withTaskLock(taskId: Long, block: suspend () -> T): T = taskLocks.withLock(taskId, block)

    /** Writes to task [taskId] running or waiting for their turn. For tests. */
    suspend fun writersFor(taskId: Long): Int = taskLocks.users(taskId)

    /** Writes to the project or label running or waiting for their turn. For tests. */
    suspend fun writersFor(entityType: String, entityId: Long): Int = entityLocks.users("$entityType:$entityId")
}
