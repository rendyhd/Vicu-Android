package com.rendyhd.vicu.data.sync

import com.rendyhd.vicu.data.local.CarrierIdStore
import com.rendyhd.vicu.data.remote.api.TaskDto
import com.rendyhd.vicu.data.remote.api.VikunjaApiException
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.util.CustomListEnvelope
import com.rendyhd.vicu.util.Logger
import com.rendyhd.vicu.util.SystemTimeSource
import com.rendyhd.vicu.util.TimeSource
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** One kind of hidden carrier task: how to search for it and how to recognise it. */
class CarrierSpec(
    val kind: String,
    /** `q` text that finds this kind's carriers; the server matches the marker name inside the description. */
    val search: String,
    /** True for a carrier of this kind, readable or not. */
    val isCarrier: (TaskDto) -> Boolean,
) {
    companion object {
        /** The synced custom lists. */
        val CUSTOM_LISTS = CarrierSpec(
            kind = "custom-lists",
            search = "vicu-custom-lists",
            isCarrier = { it.title == CustomListEnvelope.CARRIER_TITLE || CustomListEnvelope.hasMarker(it.description) },
        )
    }
}

/**
 * Finds the hidden carrier tasks without paging through the completed history on every sync
 * (A-CL-1; the desktop does the same in `carrier-discovery.ts`).
 *
 * - The ids seen so far are remembered per server ([CarrierIdStore]) and fetched directly, one
 *   `GET /tasks/{id}` each.
 * - New carriers (created by another device) are found with a `q` search for the marker name plus
 *   `done = true`, at most once per [DISCOVERY_INTERVAL] and whenever none is known yet. Vikunja
 *   2.4 matches the marker text inside the HTML comment; a literal `<!--` matches nothing, so the
 *   search text is the marker name only.
 * - A full scan of the completed tasks is only the fallback: when a remembered id is gone (404 or
 *   403), the first time, and once a day for a server whose search does not look into descriptions.
 *
 * A request that fails for any other reason (offline, a server error) is thrown: nothing can be
 * said about the carriers then, and "no carriers" could make the caller create a second one.
 */
class CarrierFinder(
    private val api: VikunjaApiService,
    private val store: CarrierIdStore,
    private val time: TimeSource = SystemTimeSource,
) {
    companion object {
        private const val TAG = "CarrierFinder"

        /** New carriers from other devices are looked for at most this often. */
        val DISCOVERY_INTERVAL = 60.seconds

        /** The completed tasks are listed in full at least this often. */
        val FULL_SCAN_INTERVAL = 24.hours

        /** The server's largest accepted page; a smaller server maximum just means more pages. */
        private const val SCAN_PAGE_SIZE = "1000"
    }

    private val mutex = Mutex()
    private val lastDiscoveryAtMs = HashMap<String, Long>()

    /** Carrier task id to the project it lives in, as last read, per kind and server. Replaced as a whole. */
    @Volatile
    private var seenProjects: Map<String, Map<Long, Long>> = emptyMap()

    /**
     * The projects of the carriers of [spec] on [server] that this process has read or created,
     * with no request (the drawer's progress rings leave them out of a project's done count).
     */
    fun knownProjects(spec: CarrierSpec, server: String): Map<Long, Long> =
        seenProjects[flightKeyOf(spec.kind, server)].orEmpty()

    /** Every carrier of [spec] on [server], ascending by id. */
    suspend fun find(spec: CarrierSpec, server: String): List<TaskDto> = mutex.withLock {
        val state = store.get(server, spec.kind)
        val found = LinkedHashMap<Long, TaskDto>()
        var gone = false

        for (id in state.ids) {
            try {
                val task = api.getTask(id)
                // Reopened or rewritten into something else: it is not a carrier any more.
                if (task.id == id && task.done && spec.isCarrier(task)) found[id] = task
            } catch (e: VikunjaApiException) {
                if (e.httpStatus == 404 || e.httpStatus == 403) gone = true else throw e
            }
        }

        val now = time.now().toEpochMilliseconds()
        val flightKey = "${spec.kind}\n$server"
        val fullScanDue = gone || isFullScanDue(state.lastFullScanAtMs, now)
        val searchDue = state.ids.isEmpty() || isDiscoveryDue(lastDiscoveryAtMs[flightKey], now)
        var lastFullScanAtMs = state.lastFullScanAtMs

        if (fullScanDue || searchDue) {
            try {
                val listing = if (fullScanDue) {
                    api.getAllTasks(
                        mapOf("filter" to "done = true", "sort_by" to "updated", "order_by" to "desc", "per_page" to SCAN_PAGE_SIZE),
                        expandSubtasks = false,
                    )
                } else {
                    api.getAllTasks(
                        mapOf("q" to spec.search, "filter" to "done = true", "per_page" to SCAN_PAGE_SIZE),
                        expandSubtasks = false,
                    )
                }
                listing.filter { it.done && spec.isCarrier(it) }.forEach { found[it.id] = it }
                lastDiscoveryAtMs[flightKey] = now
                if (fullScanDue) lastFullScanAtMs = now
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nothing to fall back on: reporting "no carriers" could make a caller create a
                // second carrier next to one that exists.
                if (found.isEmpty()) throw e
                Logger.w(TAG, "${if (fullScanDue) "Full scan" else "Search"} for ${spec.kind} carriers failed; using the remembered ones: ${e.message}")
            }
        }

        val ids = found.keys.sorted()
        store.set(server, spec.kind, ids, lastFullScanAtMs)
        seenProjects = seenProjects + (flightKey to found.mapValues { it.value.projectId })
        ids.map { found.getValue(it) }
    }

    /** A carrier this app just created (in [projectId]): fetched directly from the next load on. */
    suspend fun remember(spec: CarrierSpec, server: String, id: Long, projectId: Long? = null) = mutex.withLock {
        if (projectId != null) {
            val key = flightKeyOf(spec.kind, server)
            seenProjects = seenProjects + (key to (seenProjects[key].orEmpty() + (id to projectId)))
        }
        val state = store.get(server, spec.kind)
        if (id in state.ids) return@withLock
        store.set(server, spec.kind, state.ids + id, state.lastFullScanAtMs)
    }

    private fun flightKeyOf(kind: String, server: String) = "$kind\n$server"

    private fun isDiscoveryDue(lastMs: Long?, nowMs: Long): Boolean {
        if (lastMs == null) return true
        val age = nowMs - lastMs
        return age < 0 || age >= DISCOVERY_INTERVAL.inWholeMilliseconds
    }

    private fun isFullScanDue(lastMs: Long, nowMs: Long): Boolean {
        if (lastMs <= 0L) return true
        val age = nowMs - lastMs
        // A negative age means the clock moved back; the stored time says nothing then.
        return age < 0 || age >= FULL_SCAN_INTERVAL.inWholeMilliseconds
    }
}
