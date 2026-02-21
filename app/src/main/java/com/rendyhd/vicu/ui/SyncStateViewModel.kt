package com.rendyhd.vicu.ui

import androidx.lifecycle.ViewModel
import com.rendyhd.vicu.data.local.dao.PendingActionDao
import com.rendyhd.vicu.util.NetworkMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SyncStateViewModel @Inject constructor(
    networkMonitor: NetworkMonitor,
    pendingActionDao: PendingActionDao,
) : ViewModel() {

    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline

    val pendingCount: Flow<Int> = pendingActionDao.getPendingCount()
}
