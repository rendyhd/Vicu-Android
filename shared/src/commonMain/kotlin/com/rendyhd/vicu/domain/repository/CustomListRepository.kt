package com.rendyhd.vicu.domain.repository

import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.CustomListSyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

interface CustomListRepository {
    val lists: Flow<List<CustomList>>
    val syncStatus: StateFlow<CustomListSyncStatus>

    /**
     * True while this device has custom-list changes the server may not have yet. They live outside
     * the offline queue, and sign-out or an account switch deletes them, so they count as unsynced.
     */
    val hasUnsyncedChanges: Flow<Boolean> get() = flowOf(false)

    suspend fun upsert(customList: CustomList)
    suspend fun delete(id: String)
    suspend fun reorder(fromIndex: Int, toIndex: Int)
    suspend fun clearLocal()
    suspend fun sync(): CustomListSyncStatus
}
