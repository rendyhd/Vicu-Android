package com.rendyhd.vicu.data.sync

import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.util.Logger
import com.rendyhd.vicu.util.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * How many tasks of a project are done, for the drawer's progress rings (decision 11; the desktop
 * does the same in `project-task-counts.ts`): one request for a page of one task whose API v2
 * envelope carries the `total`. Never a listing of the completed history.
 *
 * The total is the server's raw number, hidden carrier tasks included; the caller takes the ones
 * it knows about off. It is kept for [ttl] per server and project, and for as long as the
 * `signature` the caller passes (what the phone holds of the project) is the same: a task that
 * is completed, reopened or removed changes the signature and so asks again. [invalidate] drops
 * the cache (after a sync that sent something). A read that failed is remembered for [failureTtl] and
 * answers null meanwhile. Questions that overlap share one request, and a request is not
 * cancelled when the question that started it is.
 */
class ProjectTaskCounts(
    private val api: VikunjaApiService,
    private val serverKey: suspend () -> String?,
    private val time: TimeSource,
    private val scope: CoroutineScope,
    private val ttl: Duration = 10.minutes,
    /** How long a failed read is remembered, so a screen that asks again and again does not retry in a loop. */
    private val failureTtl: Duration = 60.seconds,
) {
    private companion object {
        const val TAG = "ProjectTaskCounts"
    }

    private data class Key(val server: String, val projectId: Long)
    private class Entry(val total: Long, val atMs: Long, val signature: Any?)
    private class Flight(val signature: Any?, val request: Deferred<Long?>)

    private val lock = Mutex()
    private val cache = HashMap<Key, Entry>()
    private val flights = HashMap<Key, Flight>()
    private val failedAtMs = HashMap<Key, Long>()

    // Bumped by invalidate(): an answer asked for before it is not cached.
    private var generation = 0

    private val _invalidations = MutableStateFlow(0)

    /**
     * Counts [invalidate] calls. A screen that keeps its own record of what it already asked
     * (the drawer's rings) watches it, because a sync that sent something makes every earlier
     * answer stale without any change to the tasks the phone holds.
     */
    val invalidations: StateFlow<Int> = _invalidations.asStateFlow()

    /** The done tasks the server holds for [projectId], or null when that cannot be read. */
    suspend fun doneTotal(projectId: Long, signature: Any? = null): Long? {
        val server = serverKey() ?: return null
        val key = Key(server, projectId)
        val nowMs = time.now().toEpochMilliseconds()

        val started: Pair<Flight, Int> = lock.withLock {
            cache[key]?.let { entry ->
                // A negative age means the clock moved back; the stored time says nothing then.
                if (entry.signature == signature && nowMs - entry.atMs in 0 until ttl.inWholeMilliseconds) {
                    return entry.total
                }
            }
            failedAtMs[key]?.let { failedAt ->
                if (nowMs - failedAt in 0 until failureTtl.inWholeMilliseconds) return null
            }
            val running = flights[key]?.takeIf { it.signature == signature }
            val flight = running ?: Flight(signature, scope.async { fetch(projectId) }).also { flights[key] = it }
            flight to generation
        }
        val (flight, startedIn) = started

        val total = flight.request.await()
        lock.withLock {
            if (flights[key] === flight) flights.remove(key)
            if (startedIn == generation) {
                if (total != null) {
                    cache[key] = Entry(total, time.now().toEpochMilliseconds(), signature)
                    failedAtMs.remove(key)
                } else {
                    failedAtMs[key] = time.now().toEpochMilliseconds()
                }
            }
        }
        return total
    }

    /** Forget what was read: the next question asks the server again. */
    suspend fun invalidate() {
        lock.withLock {
            generation++
            cache.clear()
            failedAtMs.clear()
            flights.clear()
        }
        _invalidations.update { it + 1 }
    }

    private suspend fun fetch(projectId: Long): Long? = try {
        val page = api.getProjectTasksPage(projectId, mapOf("filter" to "done = true", "per_page" to "1"))
        // A response without a total reads as zero; one task on the page proves it is not.
        if (page.total < page.items.size) null else page.total
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(TAG, "Could not count the done tasks of project $projectId: ${e.message}")
        null
    }
}
