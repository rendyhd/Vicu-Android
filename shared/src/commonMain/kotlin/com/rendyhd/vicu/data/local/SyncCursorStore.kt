package com.rendyhd.vicu.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Where the incremental task refresh stands.
 *
 * @property tasksUpdatedSince the newest `updated` timestamp (the server's clock) the cache is
 * known to contain everything up to, or null when the cache was never filled.
 * @property lastFullReconcileMs when the last full reconcile finished (0 = never).
 * @property fullReconcileRequested a full reconcile was asked for (changes were thrown away, so
 * the cached rows may differ from the server in ways a delta cannot show).
 */
data class SyncCursor(
    val tasksUpdatedSince: String? = null,
    val lastFullReconcileMs: Long = 0L,
    val fullReconcileRequested: Boolean = false,
)

/**
 * Persists the [SyncCursor]. The file is excluded from backups like the database it describes:
 * restoring a cursor next to an empty cache would make every refresh skip what is missing.
 *
 * A refresh takes a [Ticket] when it starts and commits with it when it ends. [clear] (sign-out,
 * cache clear) and [requestFullReconcile] invalidate every ticket, so a refresh that was already
 * downloading when the cache was wiped cannot record a cursor for data that is no longer there.
 */
class SyncCursorStore(private val dataStore: DataStore<Preferences>) {

    private companion object {
        val KEY_SINCE = stringPreferencesKey("tasks_updated_since")
        val KEY_FULL_AT = longPreferencesKey("last_full_reconcile_ms")
        val KEY_FULL_REQUESTED = booleanPreferencesKey("full_reconcile_requested")
    }

    class Ticket internal constructor(val cursor: SyncCursor, internal val generation: Long)

    private val lock = Mutex()
    private var generation = 0L

    private suspend fun read(): SyncCursor {
        val prefs = dataStore.data.first()
        return SyncCursor(
            tasksUpdatedSince = prefs[KEY_SINCE],
            lastFullReconcileMs = prefs[KEY_FULL_AT] ?: 0L,
            fullReconcileRequested = prefs[KEY_FULL_REQUESTED] ?: false,
        )
    }

    suspend fun begin(): Ticket = lock.withLock { Ticket(read(), generation) }

    /**
     * Records the result of a refresh that started with [ticket]: [tasksUpdatedSince] (when not
     * null) and, for a full reconcile, when it finished. Returns false and stores nothing when the
     * ticket is no longer valid.
     */
    suspend fun commit(ticket: Ticket, tasksUpdatedSince: String?, fullReconcileAtMs: Long? = null): Boolean =
        lock.withLock {
            if (ticket.generation != generation) return@withLock false
            dataStore.edit { prefs ->
                if (tasksUpdatedSince != null) prefs[KEY_SINCE] = tasksUpdatedSince
                if (fullReconcileAtMs != null) {
                    prefs[KEY_FULL_AT] = fullReconcileAtMs
                    prefs.remove(KEY_FULL_REQUESTED)
                }
            }
            true
        }

    /** The next refresh must be a full reconcile (queued changes were discarded, say). */
    suspend fun requestFullReconcile() = lock.withLock {
        generation++
        dataStore.edit { it[KEY_FULL_REQUESTED] = true }
    }

    /** Forgets everything: the cache was cleared or belongs to another account. */
    suspend fun clear() = lock.withLock {
        generation++
        dataStore.edit { it.clear() }
    }
}
