package com.rendyhd.vicu.auth

import com.rendyhd.vicu.data.local.LocalDataWiper
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

sealed interface SignOutResult {
    data object Done : SignOutResult

    /** Sign-out was not done: [unsyncedChanges] queued changes would be lost and discarding was not chosen. */
    data class NeedsDiscard(val unsyncedChanges: Int) : SignOutResult
}

/**
 * Sign-out and "clear cache" for Settings, with the promise that offline work is never lost by
 * accident: both need the unsynced changes to be discarded explicitly when they would take them.
 */
class SessionCleanup(
    private val authManager: AuthManager,
    private val wiper: LocalDataWiper,
) {
    /** Entries of routine history not uploaded to the server yet (sign-out deletes them). */
    val routineHistoryCount: Flow<Int> get() = wiper.routineHistoryCount

    /**
     * Signs out and deletes local data. With queued changes and [discardUnsynced] false nothing
     * is touched and [SignOutResult.NeedsDiscard] is returned.
     *
     * Server session and token cleanup run first, then the data is wiped, and the whole
     * sequence is not cancellable: leaving the screen cancels the caller's scope as soon as the
     * auth state flips to signed-out, and an interrupted sign-out must never end with the data
     * wiped but the tokens still stored.
     */
    suspend fun signOut(discardUnsynced: Boolean): SignOutResult {
        val unsynced = wiper.unsyncedActionCount()
        if (unsynced > 0 && !discardUnsynced) return SignOutResult.NeedsDiscard(unsynced)
        withContext(NonCancellable) {
            authManager.logout()
            wiper.wipeEverything()
        }
        return SignOutResult.Done
    }

    /**
     * Clears cached tasks, projects and labels. The offline queue (and the rows it refers to)
     * and routine history waiting to be uploaded stay unless [discardUnsynced] is set.
     */
    suspend fun clearCaches(discardUnsynced: Boolean) {
        if (discardUnsynced) wiper.discardUnsyncedAndClearCaches() else wiper.clearCaches()
    }
}
