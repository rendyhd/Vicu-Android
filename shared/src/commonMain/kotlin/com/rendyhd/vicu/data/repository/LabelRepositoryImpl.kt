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
import kotlinx.coroutines.flow.Flow
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
        }

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

    private suspend fun refreshCachedTask(taskId: Long) {
        try {
            val taskDto = api.getTask(taskId)
            taskDao.upsert(with(taskMapper) { taskDto.toEntity() })
        } catch (_: Exception) {
        }
    }

    override suspend fun addToTask(taskId: Long, labelId: Long): NetworkResult<Unit> {
        val result = try {
            api.addLabelToTask(taskId, LabelTaskDto(labelId = labelId))
            NetworkResult.Success(Unit)
        } catch (e: Exception) {
            if (isRetriableNetworkError(e)) {
                queueLabelAction(labelId, "add_label", "$taskId:$labelId")
                patchTaskLabelLocally(taskId, labelId, add = true)
                return NetworkResult.Success(Unit)
            } else {
                NetworkResult.Error(e.message ?: "Failed to add label to task")
            }
        }
        if (result is NetworkResult.Success) refreshCachedTask(taskId)
        return result
    }

    override suspend fun removeFromTask(taskId: Long, labelId: Long): NetworkResult<Unit> {
        val result = try {
            api.removeLabelFromTask(taskId, labelId)
            NetworkResult.Success(Unit)
        } catch (e: Exception) {
            if (isRetriableNetworkError(e)) {
                queueLabelAction(labelId, "remove_label", "$taskId:$labelId")
                patchTaskLabelLocally(taskId, labelId, add = false)
                return NetworkResult.Success(Unit)
            } else {
                NetworkResult.Error(e.message ?: "Failed to remove label from task")
            }
        }
        if (result is NetworkResult.Success) refreshCachedTask(taskId)
        return result
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
