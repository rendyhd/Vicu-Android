package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.local.CustomListStore
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
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.CustomListSyncDocumentV1
import com.rendyhd.vicu.domain.model.CustomListSyncLocalState
import com.rendyhd.vicu.domain.model.CustomListSyncOrder
import com.rendyhd.vicu.domain.model.CustomListSyncRecord
import com.rendyhd.vicu.domain.model.CustomListSyncStatus
import com.rendyhd.vicu.domain.model.toWire
import com.rendyhd.vicu.domain.repository.CustomListRepository
import com.rendyhd.vicu.domain.repository.PlatformRepositoryHooks
import com.rendyhd.vicu.util.CustomListEnvelope
import com.rendyhd.vicu.util.SystemTimeSource
import com.rendyhd.vicu.util.TimeSource
import com.rendyhd.vicu.util.randomUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

/** The custom-list carrier (docs/cross-app-semantics-v1.md, sections 3 and 10) for [SyncedDocumentSync]. */
class CustomListCodec(private val json: Json) : SyncedDocumentCodec<CustomListSyncDocumentV1> {
    override val spec = CarrierSpec.CUSTOM_LISTS
    override val carrierTitle = CustomListEnvelope.CARRIER_TITLE
    override val messages = SyncedDocumentMessages(
        updateRequired = "Update Vicu to sync custom lists",
        malformed = "A malformed custom-list carrier was ignored; valid data was preserved",
        noInbox = "Choose an Inbox project before syncing custom lists",
        offline = "Custom-list sync is offline",
        failed = "Custom-list sync failed",
    )

    override fun parse(description: String?): CarrierRead<CustomListSyncDocumentV1> {
        val parsed = CustomListEnvelope.parse(description, json)
        return CarrierRead(
            document = parsed.document,
            error = parsed.error,
            newerVersion = (parsed.version ?: 0) > CustomListEnvelope.CURRENT_VERSION,
        )
    }

    override fun encode(document: CustomListSyncDocumentV1) = CustomListEnvelope.encode(document, json)
    override fun normalize(document: CustomListSyncDocumentV1) = CustomListEnvelope.normalize(document)
    override fun merge(local: CustomListSyncDocumentV1, server: CustomListSyncDocumentV1) = CustomListEnvelope.merge(local, server)
    override fun pruneTombstones(document: CustomListSyncDocumentV1, nowMs: Long) = CustomListEnvelope.pruneTombstones(document, nowMs)
    override fun hasContent(document: CustomListSyncDocumentV1) = document.lists.isNotEmpty()

    override fun same(left: CustomListSyncDocumentV1, right: CustomListSyncDocumentV1): Boolean =
        json.encodeToString(CustomListSyncDocumentV1.serializer(), CustomListEnvelope.normalize(left)) ==
            json.encodeToString(CustomListSyncDocumentV1.serializer(), CustomListEnvelope.normalize(right))
}

class CustomListRepositoryImpl(
    private val store: CustomListStore,
    api: VikunjaApiService,
    private val authManager: AuthManager,
    private val platformHooks: PlatformRepositoryHooks,
    json: Json,
    carrierFinder: CarrierFinder,
    private val time: TimeSource = SystemTimeSource,
) : CustomListRepository {
    override val lists = store.getAll()
    private val _syncStatus = MutableStateFlow<CustomListSyncStatus>(CustomListSyncStatus.Idle)
    override val syncStatus: StateFlow<CustomListSyncStatus> = _syncStatus
    override val hasUnsyncedChanges = store.hasUnsyncedChanges
    override val importedSavedFilters = store.importedSavedFilters

    private val engine = SyncedDocumentSync(
        codec = CustomListCodec(json),
        local = object : SyncedDocumentLocal<CustomListSyncDocumentV1> {
            override suspend fun load() = ensureState().let {
                SyncedDocumentState(it.deviceId, it.document, it.dirty, it.carrierTaskId, it.lastSyncedAt)
            }

            override suspend fun save(state: SyncedDocumentState<CustomListSyncDocumentV1>) =
                store.saveSyncState(
                    CustomListSyncLocalState(state.deviceId, state.document, state.dirty, state.carrierTaskId, state.lastSyncedAt),
                )
        },
        api = api,
        carrierFinder = carrierFinder,
        currentUser = authManager,
        serverKey = { authManager.getVikunjaUrl().orEmpty() },
        inboxProjectId = { authManager.getInboxProjectId() },
        time = time,
        onStatus = { _syncStatus.value = it.toCustomListStatus() },
        onChanged = { platformHooks.updateWidgets() },
    )

    private fun SyncedDocumentStatus.toCustomListStatus(): CustomListSyncStatus = when (this) {
        SyncedDocumentStatus.Idle -> CustomListSyncStatus.Idle
        SyncedDocumentStatus.Syncing -> CustomListSyncStatus.Syncing
        SyncedDocumentStatus.Pending -> CustomListSyncStatus.Pending
        is SyncedDocumentStatus.Offline -> CustomListSyncStatus.Offline(message)
        is SyncedDocumentStatus.Error -> CustomListSyncStatus.Error(message)
        is SyncedDocumentStatus.UpdateRequired -> CustomListSyncStatus.UpdateRequired(message)
    }

    private fun now(): Long = time.now().toEpochMilliseconds()

    private suspend fun ensureState(): CustomListSyncLocalState {
        val existing = store.getSyncState()
        if (existing != null) {
            val normalized = runCatching { CustomListEnvelope.normalize(existing.document) }.getOrNull()
            if (normalized != null) return existing.copy(document = normalized)
        }
        val deviceId = existing?.deviceId?.takeIf { it.isNotBlank() } ?: randomUuid()
        val legacyLists = store.getLegacyLists().map { it.toWire() }
        val document = if (legacyLists.isEmpty()) {
            // A device that never synced has no order of its own. Stamp the empty one with wall
            // time 0, so any real order on the carrier wins the first merge instead of being
            // overwritten by an empty order that merely looks newer.
            CustomListEnvelope.empty(deviceId, 0L)
        } else {
            CustomListEnvelope.fromLists(legacyLists, deviceId, now())
        }
        return CustomListSyncLocalState(
            deviceId = deviceId,
            document = document,
            dirty = document.lists.isNotEmpty(),
        ).also { store.saveSyncState(it) }
    }

    private suspend fun saveMutation(state: CustomListSyncLocalState) {
        store.saveSyncState(state.copy(dirty = true))
        _syncStatus.value = CustomListSyncStatus.Pending
        platformHooks.triggerSync()
        platformHooks.updateWidgets()
    }

    override suspend fun upsert(customList: CustomList) = engine.mutate {
        val state = ensureState()
        var document = CustomListEnvelope.normalize(state.document)
        // Keep the fields of the stored value that this version does not know (another app's).
        val previous = document.lists[customList.id]
        val value = customList.toWire(preserving = previous?.value)
        val isNew = previous?.value == null
        val revision = CustomListEnvelope.nextRevision(document, state.deviceId, now())
        document = document.copy(
            lists = document.lists + (value.id to (previous?.copy(value = value, revision = revision) ?: CustomListSyncRecord(value, revision))),
        )
        if (isNew && value.id !in document.order.ids) {
            document = document.copy(order = document.order.copy(
                ids = document.order.ids + value.id,
                revision = CustomListEnvelope.nextRevision(document, state.deviceId, now()),
            ))
        }
        saveMutation(state.copy(document = CustomListEnvelope.normalize(document)))
    }

    override suspend fun importLists(lists: List<CustomList>, filterIds: Collection<Long>) = engine.mutate {
        val state = ensureState()
        var document = CustomListEnvelope.normalize(state.document)
        for (list in lists) {
            val value = list.toWire()
            document = document.copy(
                lists = document.lists + (value.id to CustomListSyncRecord(value, CustomListEnvelope.nextRevision(document, state.deviceId, now()))),
            )
            document = document.copy(order = document.order.copy(
                ids = document.order.ids.filterNot { it == value.id } + value.id,
                revision = CustomListEnvelope.nextRevision(document, state.deviceId, now()),
            ))
        }
        document = document.copy(
            importedSavedFilters = CustomListEnvelope.unionImported(document.importedSavedFilters, filterIds.toList()),
        )
        saveMutation(state.copy(document = CustomListEnvelope.normalize(document)))
    }

    override suspend fun dropDuplicateImports() = engine.mutate {
        val state = ensureState()
        val document = CustomListEnvelope.normalize(state.document)
        val cleaned = CustomListEnvelope.dropDuplicateImports(document, state.deviceId, now())
        if (cleaned !== document) saveMutation(state.copy(document = CustomListEnvelope.normalize(cleaned)))
    }

    override suspend fun delete(id: String) = engine.mutate {
        val state = ensureState()
        var document = CustomListEnvelope.normalize(state.document)
        document = document.copy(
            lists = document.lists + (id to CustomListSyncRecord(
                value = null,
                revision = CustomListEnvelope.nextRevision(document, state.deviceId, now()),
            )),
        )
        document = document.copy(order = document.order.copy(
            ids = document.order.ids.filterNot { it == id },
            revision = CustomListEnvelope.nextRevision(document, state.deviceId, now()),
        ))
        saveMutation(state.copy(document = CustomListEnvelope.normalize(document)))
    }

    override suspend fun reorder(fromIndex: Int, toIndex: Int) = engine.mutate {
        val state = ensureState()
        val document = CustomListEnvelope.normalize(state.document)
        val ids = CustomListEnvelope.activeLists(document).map { it.id }.toMutableList()
        if (fromIndex !in ids.indices || toIndex !in ids.indices || fromIndex == toIndex) return@mutate
        val item = ids.removeAt(fromIndex)
        ids.add(toIndex, item)
        saveMutation(state.copy(document = document.copy(order = CustomListSyncOrder(
            ids = ids,
            revision = CustomListEnvelope.nextRevision(document, state.deviceId, now()),
            unknown = document.order.unknown,
        ))))
    }

    override suspend fun clearLocal() = engine.clear {
        store.clear()
        platformHooks.updateWidgets()
    }

    override suspend fun sync(): CustomListSyncStatus = engine.sync().toCustomListStatus()
}
