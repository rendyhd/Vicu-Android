package com.rendyhd.vicu.data.sync

import com.rendyhd.vicu.data.local.CarrierIdStore
import com.rendyhd.vicu.data.remote.api.TaskDto
import com.rendyhd.vicu.data.remote.api.VikunjaApiException
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.data.remote.api.isBrokenSearch
import com.rendyhd.vicu.util.CustomListEnvelope
import com.rendyhd.vicu.util.HiddenTasks
import com.rendyhd.vicu.util.Logger
import com.rendyhd.vicu.util.ProLedgerEnvelope
import com.rendyhd.vicu.util.SyncedPrefsEnvelope
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
    /** True for a carrier of this kind, readable or not. Decided by the marker alone, never the title. */
    val isCarrier: (TaskDto) -> Boolean,
) {
    companion object {
        /** The synced custom lists. */
        val CUSTOM_LISTS = CarrierSpec(
            kind = "custom-lists",
            search = "vicu-custom-lists",
            isCarrier = { CustomListEnvelope.hasMarker(it.description) },
        )

        /** The synced preferences (docs/cross-app-semantics-v1.md, section 11). */
        val PREFS = CarrierSpec(
            kind = "prefs",
            search = SyncedPrefsEnvelope.SEARCH,
            isCarrier = { SyncedPrefsEnvelope.hasMarker(it.description) },
        )

        /** The Pro ledger (section 21.7); read and written only while Pro is on. */
        val PRO = CarrierSpec(
            kind = "pro",
            search = ProLedgerEnvelope.SEARCH,
            isCarrier = { ProLedgerEnvelope.hasMarker(it.description) },
        )

        /** The Pro ledger's archive parts, one per quarter, read on demand. */
        val PRO_ARCHIVE = CarrierSpec(
            kind = "pro-archive",
            search = ProLedgerEnvelope.ARCHIVE_SEARCH,
            isCarrier = { ProLedgerEnvelope.hasArchiveMarker(it.description) },
        )
    }
}

/**
 * Finds the hidden carrier tasks without paging through the completed history on every sync
 * (A-CL-1; the desktop does the same in `carrier-discovery.ts`).
 *
 * - The ids seen so far are remembered per server ([CarrierIdStore]) and fetched directly, one
 *   `GET /tasks/{id}` each, done or not: a carrier someone reopened in the web UI is still the
 *   carrier, and the next write marks it done again (docs/cross-app-semantics-v1.md, section 10.2).
 * - New carriers (created by another device) are found with a `q` search for the marker name, at
 *   most once per [DISCOVERY_INTERVAL] and whenever none is known yet. Vikunja 2.4 matches the
 *   marker text inside the HTML comment, done and open tasks alike.
 * - A full scan of the completed tasks is only the fallback: when a remembered id is gone (404 or
 *   403), the first time, and once a day for a server whose search does not look into descriptions
 *   or answers it with an error ([isBrokenSearch]).
 * - Only the signed-in user's carriers count (`created_by`, section 10.3): a shared project shows
 *   other people's, which are never read, written or deleted.
 *
 * Any other request that fails (offline, a server error) is thrown: nothing can be said about the
 * carriers then, and "no carriers" could make the caller create a second one.
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

    /** Kind and server of the carriers already looked up for [knownProjects] in this process. */
    private val warmed = HashSet<String>()

    /**
     * Makes [knownProjects] complete after a cold start: when carriers of [spec] are remembered
     * for [server] but none has been read in this process yet, one [find] reads them (a request per
     * remembered carrier). Nothing when none is remembered; a failure is ignored and tried again next time.
     */
    suspend fun ensureKnown(spec: CarrierSpec, server: String, ownerId: Long) {
        val key = flightKeyOf(spec.kind, server)
        if (seenProjects[key] != null || mutex.withLock { key in warmed }) return
        if (store.get(server, spec.kind).ids.isEmpty()) return
        try {
            find(spec, server, ownerId)
            mutex.withLock { warmed += key }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Could not read the ${spec.kind} carriers for the project counts: ${e.message}")
        }
    }

    /** Every carrier of [spec] on [server] that [ownerId] created, ascending by id. */
    suspend fun find(spec: CarrierSpec, server: String, ownerId: Long): List<TaskDto> = mutex.withLock {
        fun mine(task: TaskDto) = spec.isCarrier(task) && HiddenTasks.isOwn(task, ownerId)

        val state = store.get(server, spec.kind)
        val found = LinkedHashMap<Long, TaskDto>()
        var gone = false

        for (id in state.ids) {
            try {
                val task = api.getTask(id)
                // Rewritten into something else: it is not a carrier any more. Reopened is still one.
                if (task.id == id && mine(task)) found[id] = task
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
                // A search the server answers with an error finds nothing new: the scan below, or
                // the one within the last day the remembered ids come from, stands in for it.
                suspend fun search(): List<TaskDto> = try {
                    api.getAllTasks(mapOf("q" to spec.search, "per_page" to SCAN_PAGE_SIZE), expandSubtasks = false)
                } catch (e: VikunjaApiException) {
                    if (!e.isBrokenSearch) throw e
                    Logger.w(TAG, "Search for ${spec.kind} carriers failed; going by the last full scan: ${e.message}")
                    emptyList()
                }
                val listing = if (fullScanDue) {
                    val scan = api.getAllTasks(
                        mapOf("filter" to "done = true", "sort_by" to "updated", "order_by" to "desc", "per_page" to SCAN_PAGE_SIZE),
                        expandSubtasks = false,
                    )
                    // The scan lists done tasks only; a reopened carrier nobody remembers is found
                    // by the search.
                    if (state.ids.isEmpty()) scan + search() else scan
                } else {
                    search()
                }
                listing.filter { mine(it) }.forEach { found[it.id] = it }
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

    /** A carrier this app deleted (a covered duplicate): never asked for again. */
    suspend fun forget(spec: CarrierSpec, server: String, id: Long) = mutex.withLock {
        val key = flightKeyOf(spec.kind, server)
        seenProjects[key]?.let { seenProjects = seenProjects + (key to (it - id)) }
        val state = store.get(server, spec.kind)
        if (id in state.ids) store.set(server, spec.kind, state.ids - id, state.lastFullScanAtMs)
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
