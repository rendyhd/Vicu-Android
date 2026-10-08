package com.rendyhd.vicu.data.sync

import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.util.Logger
import com.rendyhd.vicu.util.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * How many tasks of a project are done, for the drawer's progress rings (decision 11; the desktop
 * does the same in `project-task-counts.ts`): one request for a page of one task whose API v2
 * envelope carries the `total`. Never a listing of the completed history.
 *
 * The total is the server's raw number, hidden carrier tasks included; the caller takes the ones
 * it knows about off. It is kept for [ttl] per server and project, and for as long as the
 * `signature` the caller passes (what the phone holds of the project) is the same: a task that
 * is completed, reopened or removed changes the signature and so asks again. [invalidate] drops
 * the cache (after a sync). Questions that overlap share one request, and a request is not
 * cancelled when the question that started it is.
 */
class ProjectTaskCounts(
    private val api: VikunjaApiService,
    private val serverKey: suspend () -> String?,
    private val time: TimeSource,
    private val scope: CoroutineScope,
    private val ttl: Duration = 10.minutes,
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

    // Bumped by invalidate(): an answer asked for before it is not cached.
    private var generation = 0

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
            val running = flights[key]?.takeIf { it.signature == signature }
            val flight = running ?: Flight(signature, scope.async { fetch(projectId) }).also { flights[key] = it }
            flight to generation
        }
        val (flight, startedIn) = started

        val total = flight.request.await()
        lock.withLock {
            if (flights[key] === flight) flights.remove(key)
            if (total != null && startedIn == generation) {
                cache[key] = Entry(total, time.now().toEpochMilliseconds(), signature)
            }
        }
        return total
    }

    /** Forget what was read: the next question asks the server again. */
    suspend fun invalidate() {
        lock.withLock {
            generation++
            cache.clear()
            flights.clear()
        }
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
