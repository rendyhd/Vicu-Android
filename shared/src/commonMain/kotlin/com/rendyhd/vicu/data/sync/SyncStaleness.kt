package com.rendyhd.vicu.data.sync

import com.rendyhd.vicu.util.AtomicLong
import kotlinx.datetime.Clock

/**
 * Tracks when the app last completed a full network refresh, app-wide.
 *
 * List ViewModels call [isStale] from `init{}` to decide whether the background refresh is
 * worth running — Room already hydrates the UI from cache instantly, so re-syncing on every
 * ViewModel recreation (which happens often via nav save/restore) is wasteful.
 */
class SyncStaleness {
    private val lastSyncMs = AtomicLong(0L)

    fun isStale(): Boolean = Clock.System.now().toEpochMilliseconds() - lastSyncMs.get() > TTL_MS

    fun markSynced() {
        lastSyncMs.set(Clock.System.now().toEpochMilliseconds())
    }

    /** Forgets the last sync, so the next screen refreshes (cache cleared, account changed). */
    fun reset() {
        lastSyncMs.set(0L)
    }

    companion object {
        private const val TTL_MS = 60_000L
    }
}
