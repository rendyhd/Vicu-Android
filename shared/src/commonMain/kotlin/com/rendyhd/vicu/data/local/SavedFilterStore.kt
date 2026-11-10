package com.rendyhd.vicu.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** A saved filter on the server as the project list names it (cross-app semantics 3.7). */
@Serializable
data class SavedFilterRef(val id: Long, val title: String)

/**
 * What this device knows about the account's Vikunja saved filters (cross-app semantics 3.7): the
 * filters the last project refresh saw, whether this device's first connect has imported them,
 * the filters the person said Not now to, and the imported lists whose first-open notice was
 * closed. All per device and per account: sign-out and an account switch clear it.
 */
class SavedFilterStore(private val dataStore: DataStore<Preferences>) {
    private companion object {
        val KEY_KNOWN = stringPreferencesKey("known_filters")
        val KEY_FIRST_CONNECT = booleanPreferencesKey("first_connect_done")
        val KEY_DISMISSED = stringPreferencesKey("dismissed_offer")
        val KEY_NOTICE_CLOSED = stringPreferencesKey("notice_closed")
        val json = Json { ignoreUnknownKeys = true }
        val refs = ListSerializer(SavedFilterRef.serializer())
        val ids = ListSerializer(Long.serializer())
        val strings = ListSerializer(String.serializer())
    }

    private fun <T> decode(raw: String?, serializer: kotlinx.serialization.KSerializer<List<T>>): List<T> =
        raw?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    /** The saved filters the last project refresh found, in the server's order. */
    val known: Flow<List<SavedFilterRef>> = dataStore.data.map { decode(it[KEY_KNOWN], refs) }

    /** Filters the person put off with Not now; a new filter brings the offer back. */
    val dismissed: Flow<Set<Long>> = dataStore.data.map { decode(it[KEY_DISMISSED], ids).toSet() }

    /** Imported lists whose first-open notice was closed with Got it. */
    val noticeClosed: Flow<Set<String>> = dataStore.data.map { decode(it[KEY_NOTICE_CLOSED], strings).toSet() }

    suspend fun setKnown(filters: List<SavedFilterRef>) {
        dataStore.edit { it[KEY_KNOWN] = json.encodeToString(refs, filters) }
    }

    suspend fun isFirstConnectDone(): Boolean = dataStore.data.first()[KEY_FIRST_CONNECT] == true

    suspend fun markFirstConnectDone() {
        dataStore.edit { it[KEY_FIRST_CONNECT] = true }
    }

    suspend fun dismiss(filterIds: Collection<Long>) {
        dataStore.edit { prefs ->
            val now = decode(prefs[KEY_DISMISSED], ids).toSet() + filterIds
            prefs[KEY_DISMISSED] = json.encodeToString(ids, now.sorted())
        }
    }

    suspend fun closeNotice(listId: String) {
        dataStore.edit { prefs ->
            val now = decode(prefs[KEY_NOTICE_CLOSED], strings).toSet() + listId
            prefs[KEY_NOTICE_CLOSED] = json.encodeToString(strings, now.sorted())
        }
    }

    suspend fun clear() {
        dataStore.edit { it.clear() }
    }
}
