package com.rendyhd.vicu.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.data.local.SyncCursorStore
import com.rendyhd.vicu.data.local.dao.PendingActionDao
import com.rendyhd.vicu.data.remote.ServerReachability
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.ui.screens.settings.PlatformSettingsHooks
import com.rendyhd.vicu.util.NetworkMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

class SyncStateViewModel(
    private val networkMonitor: NetworkMonitor,
    private val pendingActionDao: PendingActionDao,
    private val platformSettingsHooks: PlatformSettingsHooks,
    private val syncCursor: SyncCursorStore,
    private val reachability: ServerReachability,
    private val api: VikunjaApiService,
) : ViewModel() {

    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline

    val pendingCount: Flow<Int> = pendingActionDao.getPendingCount()

    /** Changes the server refused. They wait for the user to retry or discard them. */
    val failedCount: Flow<Int> = pendingActionDao.getFailedCount()

    /**
     * True while the device has a network and the server does not answer. While this is collected
     * and the server is down it asks again now and then, so the banner goes when the server is back
     * even if nothing else sends a request; the queue is sent right then.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val serverUnreachable: Flow<Boolean> = run {
        var wasDown = false
        combine(networkMonitor.isOnline, reachability.unreachable) { online, down -> online && down }
            .distinctUntilChanged()
            .transformLatest { down ->
                emit(down)
                if (wasDown && !down && networkMonitor.isOnline.value) platformSettingsHooks.triggerImmediateSync()
                wasDown = down
                while (down) {
                    delay(SERVER_CHECK_INTERVAL)
                    try {
                        api.getServerInfo()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Still down, or answered with an error: the client has noted which.
                    }
                }
            }
    }

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

    private companion object {
        val SERVER_CHECK_INTERVAL = 30.seconds
    }
}
