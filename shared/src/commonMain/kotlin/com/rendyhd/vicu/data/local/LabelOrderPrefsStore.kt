package com.rendyhd.vicu.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Client-side ordering for labels in the drawer. Vikunja has no label position field, so the
 * order is stored locally (and does not sync across devices). See issue #6 (Item 7). Stored as a
 * comma-joined list of label ids; ids not present fall back to alphabetical order at the end.
 */
class LabelOrderPrefsStore(
    private val dataStore: DataStore<Preferences>,
) {
    private companion object {
        val KEY_ORDER = stringPreferencesKey("label_order")
    }

    fun getOrder(): Flow<List<Long>> = dataStore.data.map { p ->
        p[KEY_ORDER]
            ?.split(",")
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?: emptyList()
    }

    suspend fun setOrder(ids: List<Long>) {
        dataStore.edit { it[KEY_ORDER] = ids.joinToString(",") }
    }

    /** Forgets the order (the label ids belong to the account that is gone). */
    suspend fun clear() {
        dataStore.edit { it.clear() }
    }
}

