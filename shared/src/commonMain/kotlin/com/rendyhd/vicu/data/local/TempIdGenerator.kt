package com.rendyhd.vicu.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * Temporary ids for tasks and labels created while offline, until the server gives them real ones.
 *
 * They are negative (the server's ids are positive), strictly decreasing (-1, -2, -3, ...) and
 * remembered across restarts, so an id is never handed out twice. Tasks and labels share one
 * counter on purpose: the sync engine keeps a single temp-id to real-id map for both.
 *
 * The earlier scheme counted down from minus the start time in seconds. After a restart it began
 * again near the current time, which is inside the range an earlier run had already used when
 * that run created more ids than seconds had passed, and such ids still sit in the database
 * while their changes wait for a connection. Those older ids are around -1.8 billion, so this
 * counter, which starts at -1, cannot meet them.
 */
class TempIdGenerator(
    private val dataStore: DataStore<Preferences>,
) {
    suspend fun next(): Long {
        var allocated = 0L
        // edit {} runs one transaction at a time, so concurrent callers get different ids.
        dataStore.edit { prefs ->
            val next = (prefs[KEY_LAST] ?: 0L) - 1
            prefs[KEY_LAST] = next
            allocated = next
        }
        return allocated
    }

    /** The id handed out last, or null when none was yet. For tests and diagnostics. */
    suspend fun lastIssued(): Long? = dataStore.data.first()[KEY_LAST]

    private companion object {
        val KEY_LAST = longPreferencesKey("last_temp_id")
    }
}
