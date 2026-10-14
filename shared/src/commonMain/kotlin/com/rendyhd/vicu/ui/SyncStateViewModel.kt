package com.rendyhd.vicu.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.data.local.SyncCursorStore
import com.rendyhd.vicu.data.local.dao.PendingActionDao
import com.rendyhd.vicu.ui.screens.settings.PlatformSettingsHooks
import com.rendyhd.vicu.util.NetworkMonitor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SyncStateViewModel(
    networkMonitor: NetworkMonitor,
    private val pendingActionDao: PendingActionDao,
    private val platformSettingsHooks: PlatformSettingsHooks,
    private val syncCursor: SyncCursorStore,
) : ViewModel() {

    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline

    val pendingCount: Flow<Int> = pendingActionDao.getPendingCount()

    /** Changes the server refused. They wait for the user to retry or discard them. */
    val failedCount: Flow<Int> = pendingActionDao.getFailedCount()

    fun retryFailed() {
        viewModelScope.launch {
            pendingActionDao.retryAllFailed()
            platformSettingsHooks.triggerImmediateSync()
        }
    }

    /**
     * Drops the failed changes and syncs, so the tasks they belong to are refreshed from the
     * server (a failed create leaves a local-only row that the refresh removes).
     */
    fun discardFailed() {
        viewModelScope.launch {
            pendingActionDao.deleteFailed()
            // The rows those changes protected may differ from the server; only a full reconcile shows it.
            syncCursor.requestFullReconcile()
            platformSettingsHooks.triggerImmediateSync()
        }
    }
}
