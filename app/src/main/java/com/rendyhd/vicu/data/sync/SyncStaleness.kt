package com.rendyhd.vicu.data.sync

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks when the app last completed a full network refresh, app-wide.
 *
 * List ViewModels call [isStale] from `init{}` to decide whether the background refresh is
 * worth running — Room already hydrates the UI from cache instantly, so re-syncing on every
 * ViewModel recreation (which happens often via nav save/restore) is wasteful.
 */
@Singleton
class SyncStaleness @Inject constructor() {
    private val lastSyncMs = AtomicLong(0L)

    fun isStale(): Boolean = System.currentTimeMillis() - lastSyncMs.get() > TTL_MS

    fun markSynced() {
        lastSyncMs.set(System.currentTimeMillis())
    }

    companion object {
        private const val TTL_MS = 60_000L
    }
}
