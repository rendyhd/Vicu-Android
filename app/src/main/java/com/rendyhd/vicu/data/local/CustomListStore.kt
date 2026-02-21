package com.rendyhd.vicu.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rendyhd.vicu.domain.model.CustomList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.customListDataStore: DataStore<Preferences> by preferencesDataStore(name = "custom_lists")

@Singleton
class CustomListStore @Inject constructor(
    private val context: Context,
) {
    companion object {
        private val KEY_LISTS = stringPreferencesKey("custom_lists_json")
        private val json = Json { ignoreUnknownKeys = true }
    }

    fun getAll(): Flow<List<CustomList>> =
        context.customListDataStore.data.map { prefs ->
            val raw = prefs[KEY_LISTS] ?: return@map emptyList()
            try {
                json.decodeFromString(ListSerializer(CustomList.serializer()), raw)
            } catch (_: Exception) {
                emptyList()
            }
        }

    fun getById(id: String): Flow<CustomList?> =
        getAll().map { lists -> lists.find { it.id == id } }

    suspend fun save(list: CustomList) {
        context.customListDataStore.edit { prefs ->
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

    suspend fun delete(id: String) {
        context.customListDataStore.edit { prefs ->
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
