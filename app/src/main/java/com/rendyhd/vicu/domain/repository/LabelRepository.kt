package com.rendyhd.vicu.domain.repository

import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.util.NetworkResult
import kotlinx.coroutines.flow.Flow

interface LabelRepository {
    fun getAll(): Flow<List<Label>>

    suspend fun getById(id: Long): Label?
    suspend fun create(label: Label): NetworkResult<Label>
    suspend fun update(label: Label): NetworkResult<Label>
    suspend fun delete(labelId: Long): NetworkResult<Unit>
    suspend fun addToTask(taskId: Long, labelId: Long): NetworkResult<Unit>
    suspend fun removeFromTask(taskId: Long, labelId: Long): NetworkResult<Unit>
    suspend fun refreshAll(): NetworkResult<Unit>
}
