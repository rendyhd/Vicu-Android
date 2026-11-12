package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.auth.CurrentUser
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.ProLedgerLocalState
import com.rendyhd.vicu.data.local.ProLedgerStore
import com.rendyhd.vicu.data.remote.api.CreateTaskDto
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.data.sync.CarrierFinder
import com.rendyhd.vicu.data.sync.CarrierRead
import com.rendyhd.vicu.data.sync.CarrierSpec
import com.rendyhd.vicu.data.sync.SyncedDocumentCodec
import com.rendyhd.vicu.data.sync.SyncedDocumentLocal
import com.rendyhd.vicu.data.sync.SyncedDocumentMessages
import com.rendyhd.vicu.data.sync.SyncedDocumentState
import com.rendyhd.vicu.data.sync.SyncedDocumentStatus
import com.rendyhd.vicu.data.sync.SyncedDocumentSync
import com.rendyhd.vicu.domain.repository.PlatformRepositoryHooks
import com.rendyhd.vicu.util.HiddenTasks
import com.rendyhd.vicu.util.Logger
import com.rendyhd.vicu.util.ProLedgerEnvelope
import com.rendyhd.vicu.util.SystemTimeSource
import com.rendyhd.vicu.util.TimeSource
import com.rendyhd.vicu.util.pro.LedgerRules
import com.rendyhd.vicu.util.pro.ProLedger
import com.rendyhd.vicu.util.randomUuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The Pro ledger's carrier (docs/cross-app-semantics-v1.md section 21.7) for [SyncedDocumentSync]. */
class ProLedgerCodec : SyncedDocumentCodec<ProLedger> {
    override val spec = CarrierSpec.PRO
    override val carrierTitle = ProLedgerEnvelope.CARRIER_TITLE
    override val messages = SyncedDocumentMessages(
        updateRequired = "Update Vicu to sync Pro",
        malformed = "A damaged Pro ledger was ignored; your Pro data was kept",
        noInbox = "Choose an Inbox project before syncing Pro",
        offline = "Pro sync is offline",
        failed = "Pro sync failed",
    )

    override fun parse(description: String?): CarrierRead<ProLedger> {
        val parsed = ProLedgerEnvelope.parse(description)
        return CarrierRead(parsed.document, parsed.error, parsed.newerVersion)
    }

    override fun encode(document: ProLedger) = ProLedgerEnvelope.encode(document)
    override fun normalize(document: ProLedger) = LedgerRules.normalize(document.json)
    override fun merge(local: ProLedger, server: ProLedger) = LedgerRules.merge(local, server)
    override fun pruneTombstones(document: ProLedger, nowMs: Long) = LedgerRules.prune(document, nowMs)
    override fun hasContent(document: ProLedger) = LedgerRules.hasContent(document)
    override fun same(left: ProLedger, right: ProLedger) = left == right
}

/**
 * The Pro ledger (docs/cross-app-semantics-v1.md section 21.7), the desktop's
 * `pro-ledger-service.ts`: one hidden carrier per account (`vicu-pro:v1`, created in the Inbox only
 * when it holds something) on the generic synced-document loop, and archive parts per quarter,
 * written once records have been closed for 180 days and read on demand.
 *
 * Nothing here reaches the server while Pro is off on this device (contract 21.1, O1): [sync] and
 * the archive return at once, and a change only stays in the device's copy. Turning Pro off
 * writes nothing (O2); the copy stays as it is for when Pro is on again.
 */
class ProLedgerRepository(
    private val store: ProLedgerStore,
    private val api: VikunjaApiService,
    private val carrierFinder: CarrierFinder,
    private val currentUser: CurrentUser,
    private val serverKey: suspend () -> String,
    private val inboxProjectId: suspend () -> Long?,
    private val behaviorPrefs: BehaviorPrefsStore,
    private val platformHooks: PlatformRepositoryHooks? = null,
    private val time: TimeSource = SystemTimeSource,
) {
    constructor(
        store: ProLedgerStore,
        api: VikunjaApiService,
        carrierFinder: CarrierFinder,
        authManager: AuthManager,
        behaviorPrefs: BehaviorPrefsStore,
        platformHooks: PlatformRepositoryHooks,
        time: TimeSource,
    ) : this(
        store = store,
        api = api,
        carrierFinder = carrierFinder,
        currentUser = authManager,
        serverKey = { authManager.getVikunjaUrl().orEmpty() },
        inboxProjectId = { authManager.getInboxProjectId() },
        behaviorPrefs = behaviorPrefs,
        platformHooks = platformHooks,
        time = time,
    )

    private companion object {
        const val TAG = "ProLedger"
    }

    private val codec = ProLedgerCodec()
    private val archiveMutex = Mutex()

    private val _syncStatus = MutableStateFlow<SyncedDocumentStatus>(SyncedDocumentStatus.Idle)
    val syncStatus: StateFlow<SyncedDocumentStatus> = _syncStatus

    /** Pro is on on this device. */
    val proOn: Flow<Boolean> = behaviorPrefs.getPrefs().map { it.isProEnabled() }.distinctUntilChanged()

    /** The device's copy; empty until something is kept. */
    val ledger: Flow<ProLedger> = store.state.map(::documentOf).distinctUntilChanged()

    /** The ledger passed 70 percent of what a carrier may hold (the sync status says so). */
    val nearlyFull: Flow<Boolean> = ledger.map { ProLedgerEnvelope.jsonBytes(it) > ProLedgerEnvelope.WARN_BYTES }.distinctUntilChanged()

    /** Changes the carrier may not have yet, counted at sign-out while Pro is on (as the desktop does). */
    val hasUnsyncedChanges: Flow<Boolean> = combine(proOn, store.state) { on, state ->
        on && state != null && state.dirty && LedgerRules.hasContent(documentOf(state))
    }.distinctUntilChanged()

    private val engine = SyncedDocumentSync(
        codec = codec,
        local = object : SyncedDocumentLocal<ProLedger> {
            override suspend fun load() = loadState().let {
                SyncedDocumentState(it.deviceId, documentOf(it), it.dirty, it.carrierTaskId, it.lastSyncedAt)
            }

            override suspend fun save(state: SyncedDocumentState<ProLedger>) =
                store.save(ProLedgerLocalState(state.deviceId, state.document.json, state.dirty, state.carrierTaskId, state.lastSyncedAt))
        },
        api = api,
        carrierFinder = carrierFinder,
        currentUser = currentUser,
        serverKey = serverKey,
        inboxProjectId = inboxProjectId,
        time = time,
        onStatus = { _syncStatus.value = it },
    )

    private fun now(): Long = time.now().toEpochMilliseconds()

    /** A damaged or missing copy reads as empty: the carrier still holds what was synced. */
    private fun documentOf(state: ProLedgerLocalState?): ProLedger =
        state?.document?.let { runCatching { LedgerRules.normalize(it) }.getOrNull() } ?: LedgerRules.empty()

    private suspend fun loadState(): ProLedgerLocalState =
        store.load() ?: ProLedgerLocalState(deviceId = randomUuid()).also { store.save(it) }

    suspend fun isProOn(): Boolean = behaviorPrefs.getPrefs().first().isProEnabled()

    /** The device's copy now. */
    suspend fun current(): ProLedger = documentOf(store.load())

    /**
     * Applies [change] to the device's copy as one change (one revision stamp per record set) and
     * marks it unsynced; a sync is asked for while Pro is on. False when nothing changed.
     */
    suspend fun change(edit: (ledger: ProLedger, deviceId: String, nowMs: Long) -> ProLedger): Boolean {
        val changed = engine.mutate {
            val state = loadState()
            val current = documentOf(state)
            val next = edit(current, state.deviceId, now())
            if (next == current) return@mutate false
            store.save(state.copy(document = next.json, dirty = true))
            true
        }
        if (changed) {
            _syncStatus.value = SyncedDocumentStatus.Pending
            if (isProOn()) platformHooks?.triggerSync()
        }
        return changed
    }

    /** Sets one record of a collection; a null value deletes it (a tombstone). */
    suspend fun setRecord(collection: String, id: String, value: JsonObject?): Boolean {
        require(collection in LedgerRules.COLLECTIONS) { "Unknown ledger collection $collection" }
        return change { ledger, deviceId, nowMs -> LedgerRules.setRecord(ledger, collection, id, value, deviceId, nowMs) }
    }

    suspend fun setSettings(value: JsonObject): Boolean =
        change { ledger, deviceId, nowMs -> LedgerRules.setSettings(ledger, value, deviceId, nowMs) }

    /** A task's estimate in minutes; null clears it. */
    suspend fun setEstimate(taskId: Long, minutes: Int?): Boolean {
        require(minutes == null || minutes in 0..1440) { "An estimate is 0 to 1440 minutes" }
        return change { ledger, _, nowMs -> LedgerRules.setEstimate(ledger, taskId, minutes, nowMs) }
    }

    /** Syncs the ledger, then archives what has been closed long enough. Nothing while Pro is off. */
    suspend fun sync(): SyncedDocumentStatus {
        if (!isProOn()) return SyncedDocumentStatus.Idle
        val status = engine.sync()
        if (status == SyncedDocumentStatus.Idle && archiveClosedRecords()) return engine.sync()
        return status
    }

    /**
     * The offline queue made a task created offline real: its temporary id becomes the server id in
     * the ledger too (section 21.7). Only the device's copy changes; nothing when it never named it.
     */
    suspend fun remapTask(from: Long, to: Long) {
        val changed = engine.mutate {
            val state = loadState()
            val current = documentOf(state)
            if (LedgerRules.remapTaskId(current, from, to) == current) return@mutate false
            val deviceId = state.deviceId
            val nowMs = now()
            var next = LedgerRules.remapTaskId(current, from, to)
            // The record under its new id is a change of its own, and the temporary id gets a
            // tombstone, so a copy synced before the replay cannot bring the old key back.
            next.value("promises", to)?.let { next = LedgerRules.setRecord(next, "promises", to.toString(), it, deviceId, nowMs) }
            if (current.records("promises").containsKey(from.toString())) {
                next = LedgerRules.setRecord(next, "promises", from.toString(), null, deviceId, nowMs)
            }
            store.save(state.copy(document = next.json, dirty = true))
            true
        }
        if (changed) _syncStatus.value = SyncedDocumentStatus.Pending
    }

    /** Forgets the device's copy (the account is gone). */
    suspend fun clearLocal() = engine.clear { store.clear() }

    // --- Archive parts (section 21.7) ----------------------------------------------------------

    /**
     * Moves records closed more than 180 days ago to the archive part of their quarter: the part is
     * written first, then the records are tombstoned. True when it tombstoned something (the caller
     * syncs again). A failure leaves everything for the next sync.
     */
    private suspend fun archiveClosedRecords(): Boolean = archiveMutex.withLock {
        try {
            if (!isProOn()) return false
            val zone = time.zone()
            val today = time.now().toLocalDateTime(zone).date
            val plan = LedgerRules.archivePlan(current(), today, zone)
            if (plan.moved.isEmpty()) return false
            val owner = currentUser.id() ?: return false
            val server = serverKey()
            val existing = LinkedHashMap<String, Pair<Long, JsonObject>>()
            for (task in carrierFinder.find(CarrierSpec.PRO_ARCHIVE, server, owner)) {
                val part = ProLedgerEnvelope.parseArchive(task.description).document ?: continue
                val quarter = ProLedgerEnvelope.quarterOf(part) ?: continue
                if (quarter !in existing) existing[quarter] = task.id to part
            }
            for ((quarter, records) in plan.parts) {
                val current = existing[quarter]
                val part = LedgerRules.addToArchivePart(current?.second ?: LedgerRules.emptyArchivePart(quarter), records)
                if (current != null) {
                    writePart(current.first, quarter, part)
                } else {
                    val inbox = inboxProjectId()?.takeIf { it > 0L } ?: return false
                    val created = api.createTask(
                        inbox,
                        CreateTaskDto(
                            title = ProLedgerEnvelope.archiveTitle(quarter),
                            description = ProLedgerEnvelope.encodeArchive(part),
                            done = true,
                            dueDate = HiddenTasks.NULL_DATE,
                            reminders = emptyList(),
                        ),
                    )
                    writePart(created.id, quarter, part)
                    carrierFinder.remember(CarrierSpec.PRO_ARCHIVE, server, created.id, inbox)
                }
            }
            change { ledger, deviceId, nowMs -> LedgerRules.tombstoneMoved(ledger, plan.moved, deviceId, nowMs) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Archiving closed Pro records failed: ${e.message}")
            false
        }
    }

    private suspend fun writePart(id: Long, quarter: String, part: JsonObject) {
        api.updateTask(id, buildJsonObject {
            put("title", ProLedgerEnvelope.archiveTitle(quarter))
            put("description", ProLedgerEnvelope.encodeArchive(part))
            HiddenTasks.putForcedFields(this)
        })
    }

    /**
     * Every archive part's records merged, by collection and id (the quarterly summary). Read only,
     * on demand; empty while Pro is off.
     */
    suspend fun readArchive(): Map<String, Map<String, JsonObject>> {
        if (!isProOn()) return emptyMap()
        val owner = currentUser.id() ?: return emptyMap()
        var merged = LedgerRules.emptyArchivePart("all")
        for (task in carrierFinder.find(CarrierSpec.PRO_ARCHIVE, serverKey(), owner)) {
            val part = ProLedgerEnvelope.parseArchive(task.description).document ?: continue
            val records = LedgerRules.COLLECTIONS.associateWith { collection ->
                (part[collection] as? JsonObject)?.mapNotNull { (id, record) -> (record as? JsonObject)?.let { id to it } }?.toMap().orEmpty()
            }
            merged = LedgerRules.addToArchivePart(merged, records)
        }
        return LedgerRules.COLLECTIONS.associateWith { collection ->
            (merged[collection] as? JsonObject)?.mapNotNull { (id, record) -> (record as? JsonObject)?.let { id to it } }?.toMap().orEmpty()
        }
    }
}
