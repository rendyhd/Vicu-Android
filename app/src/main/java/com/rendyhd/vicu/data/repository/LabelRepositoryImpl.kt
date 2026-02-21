package com.rendyhd.vicu.data.repository

import android.content.Context
import com.rendyhd.vicu.data.local.dao.LabelDao
import com.rendyhd.vicu.data.local.dao.PendingActionDao
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.data.mapper.LabelMapper
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.data.remote.api.LabelTaskDto
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.isRetriableNetworkError
import com.rendyhd.vicu.worker.SyncScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LabelRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val labelDao: LabelDao,
    private val taskDao: TaskDao,
    private val pendingActionDao: PendingActionDao,
    private val api: VikunjaApiService,
    private val labelMapper: LabelMapper,
    private val taskMapper: TaskMapper,
    private val json: Json,
) : LabelRepository {

    private val tempIdCounter = AtomicLong(-(System.currentTimeMillis() / 1000 + 1_000_000))

    private suspend fun queueLabelAction(entityId: Long, actionType: String, payload: String) {
        val action = PendingActionEntity(
            entityType = "label",
            entityId = entityId,
            actionType = actionType,
            payload = payload,
            createdAt = DateUtils.nowIso(),
            updatedAt = DateUtils.nowIso(),
        )
        if (actionType == "create") {
            pendingActionDao.insert(action)
        } else {
            pendingActionDao.replaceForEntity("label", entityId, action)
        }
        SyncScheduler.enqueueWhenOnline(context)
    }

    override fun getAll(): Flow<List<Label>> =
        labelDao.getAll().map { entities ->
            entities.map { with(labelMapper) { it.toDomain() } }
        }

    override suspend fun getById(id: Long): Label? =
        labelDao.getById(id)?.let { with(labelMapper) { it.toDomain() } }

    override suspend fun create(label: Label): NetworkResult<Label> {
        return try {
            val dto = with(labelMapper) { label.toDto() }
            val responseDto = api.createLabel(dto)
            val entity = with(labelMapper) { responseDto.toEntity() }
            labelDao.upsert(entity)
            NetworkResult.Success(with(labelMapper) { entity.toDomain() })
        } catch (e: Exception) {
            if (isRetriableNetworkError(e)) {
                val tempId = tempIdCounter.decrementAndGet()
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
                NetworkResult.Error(e.localizedMessage ?: "Failed to create label")
            }
        }
    }

    override suspend fun update(label: Label): NetworkResult<Label> {
        return try {
            val dto = with(labelMapper) { label.toDto() }
            val responseDto = api.updateLabel(label.id, dto)
            val entity = with(labelMapper) { responseDto.toEntity() }
            labelDao.upsert(entity)
            NetworkResult.Success(with(labelMapper) { entity.toDomain() })
        } catch (e: Exception) {
            if (isRetriableNetworkError(e)) {
                // Optimistic local update
                val entity = with(labelMapper) { label.toEntity() }
                labelDao.upsert(entity)
                queueLabelAction(label.id, "update", json.encodeToString(Label.serializer(), label))
                NetworkResult.Success(label)
            } else {
                NetworkResult.Error(e.localizedMessage ?: "Failed to update label")
            }
        }
    }

    override suspend fun delete(labelId: Long): NetworkResult<Unit> {
        return try {
            labelDao.deleteById(labelId)
            api.deleteLabel(labelId)
            NetworkResult.Success(Unit)
        } catch (e: Exception) {
            if (isRetriableNetworkError(e)) {
                // Local delete already done
                queueLabelAction(labelId, "delete", "")
                NetworkResult.Success(Unit)
            } else {
                NetworkResult.Error(e.localizedMessage ?: "Failed to delete label")
            }
        }
    }

    override suspend fun addToTask(taskId: Long, labelId: Long): NetworkResult<Unit> {
        return try {
            api.addLabelToTask(taskId, LabelTaskDto(labelId = labelId))
            // Re-fetch task to get updated labels
            val taskDto = api.getTask(taskId)
            val taskEntity = with(taskMapper) { taskDto.toEntity() }
            taskDao.upsert(taskEntity)
            NetworkResult.Success(Unit)
        } catch (e: Exception) {
            if (isRetriableNetworkError(e)) {
                queueLabelAction(labelId, "add_label", "$taskId:$labelId")
                NetworkResult.Success(Unit)
            } else {
                NetworkResult.Error(e.localizedMessage ?: "Failed to add label to task")
            }
        }
    }

    override suspend fun removeFromTask(taskId: Long, labelId: Long): NetworkResult<Unit> {
        return try {
            api.removeLabelFromTask(taskId, labelId)
            // Re-fetch task to get updated labels
            val taskDto = api.getTask(taskId)
            val taskEntity = with(taskMapper) { taskDto.toEntity() }
            taskDao.upsert(taskEntity)
            NetworkResult.Success(Unit)
        } catch (e: Exception) {
            if (isRetriableNetworkError(e)) {
                queueLabelAction(labelId, "remove_label", "$taskId:$labelId")
                NetworkResult.Success(Unit)
            } else {
                NetworkResult.Error(e.localizedMessage ?: "Failed to remove label from task")
            }
        }
    }

    override suspend fun refreshAll(): NetworkResult<Unit> {
        return try {
            val dtos = api.getAllLabels()
            val entities = dtos.map { with(labelMapper) { it.toEntity() } }
            labelDao.upsertAll(entities)
            NetworkResult.Success(Unit)
        } catch (e: Exception) {
            NetworkResult.Error(e.localizedMessage ?: "Failed to refresh labels")
        }
    }
}
