package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.local.CustomListStore
import com.rendyhd.vicu.data.remote.api.CreateTaskDto
import com.rendyhd.vicu.data.remote.api.TaskDto
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
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
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.isRetriableNetworkError
import com.rendyhd.vicu.util.randomUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CustomListRepositoryImpl(
    private val store: CustomListStore,
    private val api: VikunjaApiService,
    private val authManager: AuthManager,
    private val platformHooks: PlatformRepositoryHooks,
    private val json: Json,
) : CustomListRepository {
    override val lists = store.getAll()
    private val _syncStatus = MutableStateFlow<CustomListSyncStatus>(CustomListSyncStatus.Idle)
    override val syncStatus: StateFlow<CustomListSyncStatus> = _syncStatus
    private val mutationMutex = Mutex()
    private val syncMutex = Mutex()
    private var clearGeneration = 0L

    private suspend fun ensureState(): CustomListSyncLocalState {
        val existing = store.getSyncState()
        if (existing != null) {
            val normalized = runCatching { CustomListEnvelope.normalize(existing.document) }.getOrNull()
            if (normalized != null) return existing.copy(document = normalized)
        }
        val deviceId = existing?.deviceId?.takeIf { it.isNotBlank() } ?: randomUuid()
        val document = CustomListEnvelope.fromLists(
            store.getLegacyLists().map { it.toWire() },
            deviceId,
            Clock.System.now().toEpochMilliseconds(),
        )
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

    override suspend fun upsert(customList: CustomList) = mutationMutex.withLock {
        val state = ensureState()
        var document = CustomListEnvelope.normalize(state.document)
        // Keep the fields of the stored value that this version does not know (another app's).
        val value = customList.toWire(preserving = document.lists[customList.id]?.value)
        val isNew = document.lists[value.id]?.value == null
        document = document.copy(
            lists = document.lists + (value.id to CustomListSyncRecord(
                value = value,
                revision = CustomListEnvelope.nextRevision(document, state.deviceId, Clock.System.now().toEpochMilliseconds()),
            )),
        )
        if (isNew && value.id !in document.order.ids) {
            document = document.copy(order = CustomListSyncOrder(
                ids = document.order.ids + value.id,
                revision = CustomListEnvelope.nextRevision(document, state.deviceId, Clock.System.now().toEpochMilliseconds()),
            ))
        }
        saveMutation(state.copy(document = CustomListEnvelope.normalize(document)))
    }

    override suspend fun delete(id: String) = mutationMutex.withLock {
        val state = ensureState()
        var document = CustomListEnvelope.normalize(state.document)
        document = document.copy(
            lists = document.lists + (id to CustomListSyncRecord(
                value = null,
                revision = CustomListEnvelope.nextRevision(document, state.deviceId, Clock.System.now().toEpochMilliseconds()),
            )),
        )
        document = document.copy(order = CustomListSyncOrder(
            ids = document.order.ids.filterNot { it == id },
            revision = CustomListEnvelope.nextRevision(document, state.deviceId, Clock.System.now().toEpochMilliseconds()),
        ))
        saveMutation(state.copy(document = CustomListEnvelope.normalize(document)))
    }

    override suspend fun reorder(fromIndex: Int, toIndex: Int) = mutationMutex.withLock {
        val state = ensureState()
        val document = CustomListEnvelope.normalize(state.document)
        val ids = CustomListEnvelope.activeLists(document).map { it.id }.toMutableList()
        if (fromIndex !in ids.indices || toIndex !in ids.indices || fromIndex == toIndex) return@withLock
        val item = ids.removeAt(fromIndex)
        ids.add(toIndex, item)
        saveMutation(state.copy(document = document.copy(order = CustomListSyncOrder(
            ids = ids,
            revision = CustomListEnvelope.nextRevision(document, state.deviceId, Clock.System.now().toEpochMilliseconds()),
        ))))
    }

    override suspend fun clearLocal() = mutationMutex.withLock {
        clearGeneration++
        store.clear()
        _syncStatus.value = CustomListSyncStatus.Idle
        platformHooks.updateWidgets()
    }

    private suspend fun carriers(): Triple<List<Pair<TaskDto, CustomListSyncDocumentV1>>, Int, Int?> {
        val valid = mutableListOf<Pair<TaskDto, CustomListSyncDocumentV1>>()
        var malformed = 0
        var futureVersion: Int? = null
        api.getAllTasks(mapOf("filter" to "done = true", "sort_by" to "updated", "order_by" to "desc"))
            .filter { it.title == CustomListEnvelope.CARRIER_TITLE || CustomListEnvelope.hasMarker(it.description) }
            .forEach { task ->
                val parsed = CustomListEnvelope.parse(task.description, json)
                when {
                    parsed.document != null -> valid += task to parsed.document
                    parsed.version != null && parsed.version > CustomListEnvelope.CURRENT_VERSION -> {
                        futureVersion = maxOf(futureVersion ?: 0, parsed.version)
                    }
                    else -> malformed++
                }
            }
        return Triple(valid, malformed, futureVersion)
    }

    private suspend fun writeCarrier(id: Long, document: CustomListSyncDocumentV1) {
        api.updateTask(id, buildJsonObject {
            put("title", CustomListEnvelope.CARRIER_TITLE)
            put("description", CustomListEnvelope.encode(document, json))
            put("done", true)
            put("due_date", "0001-01-01T00:00:00Z")
            put("repeat_after", 0)
            put("repeat_mode", 0)
            put("reminders", JsonArray(emptyList()))
        })
    }

    private suspend fun createCarrier(projectId: Long, document: CustomListSyncDocumentV1): Long {
        val task = api.createTask(projectId, CreateTaskDto(
            title = CustomListEnvelope.CARRIER_TITLE,
            description = CustomListEnvelope.encode(document, json),
            done = true,
            dueDate = "0001-01-01T00:00:00Z",
            reminders = emptyList(),
        ))
        writeCarrier(task.id, document)
        return task.id
    }

    private fun same(left: CustomListSyncDocumentV1, right: CustomListSyncDocumentV1): Boolean =
        json.encodeToString(CustomListSyncDocumentV1.serializer(), CustomListEnvelope.normalize(left)) ==
            json.encodeToString(CustomListSyncDocumentV1.serializer(), CustomListEnvelope.normalize(right))

    override suspend fun sync(): CustomListSyncStatus = syncMutex.withLock {
        _syncStatus.value = CustomListSyncStatus.Syncing
        try {
            val generation = clearGeneration
            val (remoteCarriers, malformed, futureVersion) = carriers()
            if (generation != clearGeneration) {
                return@withLock CustomListSyncStatus.Idle.also { _syncStatus.value = it }
            }
            if (futureVersion != null) {
                return@withLock CustomListSyncStatus.UpdateRequired().also { _syncStatus.value = it }
            }

            lateinit var state: CustomListSyncLocalState
            lateinit var merged: CustomListSyncDocumentV1
            var hadPendingLocalChanges = false
            // Hold the mutation lock only for the local read/merge/persist. Network calls
            // remain outside it, so offline-first edits never wait for a sync request.
            mutationMutex.withLock {
                state = ensureState()
                hadPendingLocalChanges = state.dirty
                merged = CustomListEnvelope.normalize(state.document)
                remoteCarriers.forEach { (_, document) -> merged = CustomListEnvelope.merge(merged, document) }
                state = state.copy(document = merged, dirty = hadPendingLocalChanges)
                store.saveSyncState(state)
            }

            val canonical = remoteCarriers.minByOrNull { it.first.id }
            var carrierId = canonical?.first?.id
            val needsWrite = if (canonical == null) {
                merged.lists.isNotEmpty() || hadPendingLocalChanges
            } else {
                !same(canonical.second, merged)
            }
            if (needsWrite) {
                if (carrierId != null) {
                    writeCarrier(carrierId, merged)
                } else {
                    val inboxId = authManager.getInboxProjectId() ?: 0L
                    require(inboxId > 0) { "Choose an Inbox project before syncing custom lists" }
                    carrierId = createCarrier(inboxId, merged)
                }
            }

            if (carrierId != null) {
                val verifiedCarrierId = carrierId
                val verified = CustomListEnvelope.parse(api.getTask(verifiedCarrierId).description, json)
                requireNotNull(verified.document) { verified.error ?: "Cannot verify custom-list carrier" }
                var pending = false
                mutationMutex.withLock mutationLock@{
                    if (generation != clearGeneration) return@mutationLock
                    val latest = ensureState()
                    val converged = CustomListEnvelope.merge(
                        CustomListEnvelope.merge(merged, verified.document),
                        latest.document,
                    )
                    pending = !same(converged, verified.document)
                    merged = converged
                    store.saveSyncState(latest.copy(
                        document = converged,
                        dirty = pending,
                        carrierTaskId = verifiedCarrierId,
                        lastSyncedAt = if (pending) latest.lastSyncedAt else DateUtils.nowIso(),
                    ))
                }
                if (generation != clearGeneration) {
                    return@withLock CustomListSyncStatus.Idle.also { _syncStatus.value = it }
                }
                if (pending) {
                    return@withLock CustomListSyncStatus.Pending.also { _syncStatus.value = it }
                }
            } else {
                var pending = false
                mutationMutex.withLock mutationLock@{
                    if (generation != clearGeneration) return@mutationLock
                    val latest = ensureState()
                    val converged = CustomListEnvelope.merge(merged, latest.document)
                    pending = !same(converged, merged)
                    merged = converged
                    store.saveSyncState(latest.copy(
                        document = converged,
                        dirty = pending,
                        carrierTaskId = null,
                        lastSyncedAt = if (pending) latest.lastSyncedAt else DateUtils.nowIso(),
                    ))
                }
                if (generation != clearGeneration) {
                    return@withLock CustomListSyncStatus.Idle.also { _syncStatus.value = it }
                }
                if (pending) {
                    return@withLock CustomListSyncStatus.Pending.also { _syncStatus.value = it }
                }
            }

            platformHooks.updateWidgets()
            val status = if (malformed > 0) {
                CustomListSyncStatus.Error("A malformed custom-list carrier was ignored; valid data was preserved")
            } else {
                CustomListSyncStatus.Idle
            }
            _syncStatus.value = status
            status
        } catch (error: Exception) {
            val status = if (isRetriableNetworkError(error)) {
                CustomListSyncStatus.Offline(error.message ?: "Custom-list sync is offline")
            } else {
                CustomListSyncStatus.Error(error.message ?: "Custom-list sync failed")
            }
            runCatching {
                val state = ensureState()
                store.saveSyncState(state.copy(dirty = true))
            }
            _syncStatus.value = status
            status
        }
    }
}
