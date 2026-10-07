package com.rendyhd.vicu.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
 *
 * It also remembers which real id each recent create got ([rememberRealId]). A screen that still
 * shows a task under its temporary id after the sync swapped in the created one, or a change
 * queued for it just then, finds the task that way instead of being lost.
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

    /** Records that the create of [tempId] made [realId] on the server. The newest [MAX_REMEMBERED] are kept. */
    suspend fun rememberRealId(tempId: Long, realId: Long) {
        dataStore.edit { prefs ->
            val kept = decodeMap(prefs[KEY_REAL_IDS]).filterKeys { it != tempId }.toList()
            prefs[KEY_REAL_IDS] = encodeMap((kept + (tempId to realId)).takeLast(MAX_REMEMBERED))
        }
    }

    /** The server's id for the task or label created as [tempId], when its create has gone through. */
    suspend fun realIdFor(tempId: Long): Long? = realIds()[tempId]

    /** Every remembered temporary id with the server's id it became. */
    suspend fun realIds(): Map<Long, Long> = decodeMap(dataStore.data.first()[KEY_REAL_IDS])

    private fun decodeMap(raw: String?): Map<Long, Long> =
        raw.orEmpty().split(',').mapNotNull { entry ->
            val (temp, real) = entry.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            val tempId = temp.toLongOrNull() ?: return@mapNotNull null
            val realId = real.toLongOrNull() ?: return@mapNotNull null
            tempId to realId
        }.toMap(LinkedHashMap())

    private fun encodeMap(entries: List<Pair<Long, Long>>): String =
        entries.joinToString(",") { (temp, real) -> "$temp:$real" }

    private companion object {
        val KEY_LAST = longPreferencesKey("last_temp_id")
        val KEY_REAL_IDS = stringPreferencesKey("real_ids")

        /** Enough for any backlog a device builds up offline; a temporary id is never reused. */
        const val MAX_REMEMBERED = 500
    }
}
