package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.local.TempIdGenerator
import com.rendyhd.vicu.data.local.dao.LabelDao
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.local.dao.PendingActionDao
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.data.mapper.LabelMapper
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.data.remote.api.LabelTaskDto
import com.rendyhd.vicu.data.remote.api.MergePatches
import com.rendyhd.vicu.data.remote.api.VikunjaApiException
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.data.sync.LabelRefresher
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.PlatformRepositoryHooks
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.isNetworkFailure
import com.rendyhd.vicu.util.isRetriableNetworkError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

class LabelRepositoryImpl(
    private val labelDao: LabelDao,
    private val taskDao: TaskDao,
    private val pendingActionDao: PendingActionDao,
    private val api: VikunjaApiService,
    private val labelMapper: LabelMapper,
    private val taskMapper: TaskMapper,
    private val platformHooks: PlatformRepositoryHooks,
    private val json: Json,
    private val tempIds: TempIdGenerator,
    private val labelRefresher: LabelRefresher,
    /** Sends a change to an existing task or queues it; shared with the task repository. */
    private val writeGate: TaskWriteGate = TaskWriteGate(pendingActionDao),
    /** Where Room rows are mapped to domain models: off the main thread; tests pass an unconfined one. */
    private val mappingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : LabelRepository {

    private suspend fun queueLabelAction(entityId: Long, actionType: String, payload: String) {
        val action = PendingActionEntity(
            entityType = "label",
            entityId = entityId,
            actionType = actionType,
            payload = payload,
            createdAt = DateUtils.nowIso(),
            updatedAt = DateUtils.nowIso(),
        )
        if (actionType == "create" || actionType == "add_label" || actionType == "remove_label") {
            pendingActionDao.insert(action)
        } else {
            pendingActionDao.queuePatchActionMerging(action)
        }
        platformHooks.triggerSync()
    }

    private suspend fun patchTaskLabelLocally(taskId: Long, labelId: Long, add: Boolean) {
        val entity = taskDao.getByIdSync(taskId) ?: return
        if (add) {
            val labelDto = labelDao.getById(labelId)
                ?.let { with(labelMapper) { it.toDomain() } }
                ?.let { with(labelMapper) { it.toDto() } } ?: return
            taskDao.upsert(with(taskMapper) { entity.withLabelAdded(labelDto) })
        } else {
            taskDao.upsert(with(taskMapper) { entity.withLabelRemoved(labelId) })
        }
    }

    override fun getAll(): Flow<List<Label>> =
        labelDao.getAll().map { entities ->
            entities.map { with(labelMapper) { it.toDomain() } }
        }.flowOn(mappingDispatcher)

    override suspend fun getById(id: Long): Label? =
        labelDao.getById(id)?.let { with(labelMapper) { it.toDomain() } }

    override suspend fun create(label: Label): NetworkResult<Label> {
        return try {
            val dto = with(labelMapper) { label.toCreateDto() }
            val responseDto = api.createLabel(dto)
            val entity = with(labelMapper) { responseDto.toEntity() }
            labelDao.upsert(entity)
            NetworkResult.Success(with(labelMapper) { entity.toDomain() })
        } catch (e: Exception) {
            if (isRetriableNetworkError(e)) {
                val tempId = tempIds.next()
                val localLabel = label.copy(
                    id = tempId,
                    created = DateUtils.nowIso(),
                    updated = DateUtils.nowIso(),
                )
                val entity = with(labelMapper) { localLabel.toEntity() }
                labelDao.upsert(entity)
                queueLabelAction(tempId, "create", json.encodeToString(Label.serializer(), localLabel))
                NetworkResult.Success(localLabel)
            } else {
                NetworkResult.Error(e.message ?: "Failed to create label")
            }
        }
    }

    override suspend fun update(label: Label): NetworkResult<Label> {
        val previousEntity = labelDao.getById(label.id)
        val previous = previousEntity?.let { with(labelMapper) { it.toDomain() } }
        val patch = MergePatches.label(previous, label)
        val patchPayload = json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            patch,
        )
        if (label.id < 0L) {
            labelDao.upsert(with(labelMapper) { label.toEntity() })
            queueLabelAction(
                label.id,
                "update",
                json.encodeToString(Label.serializer(), label),
            )
            return NetworkResult.Success(label)
        }
        if (patch.isEmpty()) return NetworkResult.Success(label)
        return try {
            val responseDto = api.updateLabel(label.id, patch)
            val entity = with(labelMapper) { responseDto.toEntity() }
            labelDao.upsert(entity)
            NetworkResult.Success(with(labelMapper) { entity.toDomain() })
        } catch (e: Exception) {
            if (isRetriableNetworkError(e)) {
                val entity = with(labelMapper) { label.toEntity() }
                labelDao.upsert(entity)
                queueLabelAction(label.id, "update", patchPayload)
                NetworkResult.Success(label)
            } else {
                NetworkResult.Error(e.message ?: "Failed to update label")
            }
        }
    }

    override suspend fun delete(labelId: Long): NetworkResult<Unit> {
        if (labelId < 0L) {
            labelDao.deleteById(labelId)
            queueLabelAction(labelId, "delete", "")
            return NetworkResult.Success(Unit)
        }
        return try {
            labelDao.deleteById(labelId)
            api.deleteLabel(labelId)
            NetworkResult.Success(Unit)
        } catch (e: Exception) {
            if (isRetriableNetworkError(e)) {
                queueLabelAction(labelId, "delete", "")
                NetworkResult.Success(Unit)
            } else {
                NetworkResult.Error(e.message ?: "Failed to delete label")
            }
        }
    }

    /**
     * The cached task after a label change reached the server: the server's row, unless the local
     * row holds changes the server does not have yet (queued or failed). Overwriting that row would
     * show the old values until the queue drains (the refreshers leave such rows alone for the same
     * reason), so only the label change is applied to it. Also when the task cannot be read back.
     */
    private suspend fun refreshCachedTask(taskId: Long, labelId: Long, add: Boolean) {
        if (taskId in pendingActionDao.getTaskIdsWithPendingActions()) {
            patchTaskLabelLocally(taskId, labelId, add)
            return
        }
        try {
            val taskDto = api.getTask(taskId)
            taskDao.upsert(with(taskMapper) { taskDto.toEntity() })
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            patchTaskLabelLocally(taskId, labelId, add)
        }
    }

    override suspend fun addToTask(taskId: Long, labelId: Long): NetworkResult<Unit> =
        changeTaskLabel(taskId, labelId, add = true)

    override suspend fun removeFromTask(taskId: Long, labelId: Long): NetworkResult<Unit> =
        changeTaskLabel(taskId, labelId, add = false)

    /**
     * Adds or removes a label on a task, through the same gate as the task's own changes: queued
     * while a change for the task waits (it must not overtake a queued add or remove of the same
     * label, nor a queued create or edit of the task), sent otherwise.
     */
    private suspend fun changeTaskLabel(taskId: Long, labelId: Long, add: Boolean): NetworkResult<Unit> {
        val queue: suspend () -> NetworkResult<Unit> = {
            queueLabelAction(labelId, if (add) "add_label" else "remove_label", "$taskId:$labelId")
            patchTaskLabelLocally(taskId, labelId, add)
            NetworkResult.Success(Unit)
        }
        // A task or label created offline has no id the server knows yet (sent as it is, the
        // request is refused and the label is lost). The change waits in the queue for the create,
        // and the sync moves it to the real ids.
        if (taskId < 0L || labelId < 0L) return queue()
        return writeGate.sendOrQueue(
            taskId = taskId,
            send = {
                if (add) {
                    api.addLabelToTask(taskId, LabelTaskDto(labelId = labelId))
                } else {
                    api.removeLabelFromTask(taskId, labelId)
                }
                refreshCachedTask(taskId, labelId, add)
                NetworkResult.Success(Unit)
            },
            queue = queue,
            refused = { e ->
                NetworkResult.Error(
                    e.message ?: if (add) "Failed to add label to task" else "Failed to remove label from task",
                )
            },
        )
    }

    override suspend fun refreshAll(): NetworkResult<Unit> {
        return try {
            labelRefresher.refresh()
            NetworkResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            NetworkResult.Error(
                message = if (isNetworkFailure(e)) "Can't reach the server" else e.message ?: "Failed to refresh labels",
                code = (e as? VikunjaApiException)?.httpStatus,
                offline = isNetworkFailure(e),
            )
        }
    }
}
