package com.rendyhd.vicu.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.CustomListSyncLocalState
import com.rendyhd.vicu.domain.model.toDomain
import com.rendyhd.vicu.util.CustomListEnvelope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class CustomListStore(
    private val dataStore: DataStore<Preferences>,
) {
    companion object {
        private val KEY_LISTS = stringPreferencesKey("custom_lists_json")
        private val KEY_SYNC_STATE = stringPreferencesKey("custom_lists_sync_v1")
        private val json = Json { ignoreUnknownKeys = true }
    }

    fun getAll(): Flow<List<CustomList>> =
        dataStore.data.map { prefs ->
            prefs[KEY_SYNC_STATE]?.let { raw ->
                runCatching {
                    val state = json.decodeFromString<CustomListSyncLocalState>(raw)
                    return@map CustomListEnvelope.activeLists(state.document).map { it.toDomain() }
                }
            }
            val raw = prefs[KEY_LISTS] ?: return@map emptyList()
            try {
                json.decodeFromString(ListSerializer(CustomList.serializer()), raw)
            } catch (_: Exception) {
                emptyList()
            }
        }

    /**
     * True while the lists hold changes the server may not have: a sync state marked dirty (an edit
     * not written to the carrier yet, or a sync that failed), or lists from before custom lists were
     * synced that were never uploaded. A device without any custom list has nothing to lose.
     */
    val hasUnsyncedChanges: Flow<Boolean> = dataStore.data.map { prefs ->
        val state = prefs[KEY_SYNC_STATE]?.let { raw ->
            runCatching { json.decodeFromString<CustomListSyncLocalState>(raw) }.getOrNull()
        }
        if (state != null) {
            state.dirty && state.document.lists.isNotEmpty()
        } else {
            val legacy = prefs[KEY_LISTS] ?: return@map false
            runCatching { json.decodeFromString(ListSerializer(CustomList.serializer()), legacy).isNotEmpty() }
                .getOrDefault(false)
        }
    }

    fun getById(id: String): Flow<CustomList?> =
        getAll().map { lists -> lists.find { it.id == id } }

    suspend fun getLegacyLists(): List<CustomList> {
        val prefs = dataStore.data.first()
        val raw = prefs[KEY_LISTS] ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(CustomList.serializer()), raw) }.getOrDefault(emptyList())
    }

    suspend fun getSyncState(): CustomListSyncLocalState? {
        val raw = dataStore.data.first()[KEY_SYNC_STATE] ?: return null
        return runCatching { json.decodeFromString<CustomListSyncLocalState>(raw) }.getOrNull()
    }

    suspend fun saveSyncState(state: CustomListSyncLocalState) {
        val lists = CustomListEnvelope.activeLists(state.document).map { it.toDomain() }
        dataStore.edit { prefs ->
            prefs[KEY_SYNC_STATE] = json.encodeToString(CustomListSyncLocalState.serializer(), state)
            // Maintain the old cache for widgets and downgrade safety.
            prefs[KEY_LISTS] = json.encodeToString(ListSerializer(CustomList.serializer()), lists)
        }
    }

    suspend fun save(list: CustomList) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_LISTS]?.let {
                try {
                    json.decodeFromString(ListSerializer(CustomList.serializer()), it).toMutableList()
                } catch (_: Exception) {
                    mutableListOf()
                }
            } ?: mutableListOf()

            val index = current.indexOfFirst { it.id == list.id }
            if (index >= 0) {
                current[index] = list
            } else {
                current.add(list)
            }

            prefs[KEY_LISTS] = json.encodeToString(ListSerializer(CustomList.serializer()), current)
        }
    }

    suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    suspend fun reorder(fromIndex: Int, toIndex: Int) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_LISTS]?.let {
                try {
                    json.decodeFromString(ListSerializer(CustomList.serializer()), it).toMutableList()
                } catch (_: Exception) {
                    mutableListOf()
                }
            } ?: return@edit

            if (fromIndex !in current.indices || toIndex !in current.indices) return@edit
            val item = current.removeAt(fromIndex)
            current.add(toIndex, item)
            prefs[KEY_LISTS] = json.encodeToString(ListSerializer(CustomList.serializer()), current)
        }
    }

    suspend fun delete(id: String) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_LISTS]?.let {
                try {
                    json.decodeFromString(ListSerializer(CustomList.serializer()), it).toMutableList()
                } catch (_: Exception) {
                    mutableListOf()
                }
            } ?: return@edit

            current.removeAll { it.id == id }
            prefs[KEY_LISTS] = json.encodeToString(ListSerializer(CustomList.serializer()), current)
        }
    }
}

