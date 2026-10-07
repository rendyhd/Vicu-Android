package com.rendyhd.vicu.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.Preferences.Key
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What is remembered about the hidden "carrier" tasks (the synced custom lists) of the server the
 * app is signed in to: their ids, and when the completed tasks were last listed in full.
 *
 * Finding a carrier used to mean paging through every completed task on every sync. With the ids
 * remembered each carrier is one `GET /tasks/{id}`; see [com.rendyhd.vicu.data.sync.CarrierFinder].
 *
 * The state is keyed by server, so an id from one server is never asked of another, and
 * [clear] runs when the account is wiped (sign-out, another account). It is device-local and
 * excluded from backups: a restored copy would only cost one full scan.
 */
class CarrierIdStore(private val dataStore: DataStore<Preferences>) {

    @Serializable
    data class State(
        val server: String = "",
        /** Carrier task ids seen on [server], ascending. */
        val ids: List<Long> = emptyList(),
        /** When the completed tasks were last listed in full (ms since the epoch); 0 when never. */
        val lastFullScanAtMs: Long = 0L,
    )

    private companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun key(kind: String): Key<String> = stringPreferencesKey("carriers_$kind")
    }

    /** The state for [kind] on [server]; empty when nothing is stored or it belongs to another server. */
    suspend fun get(server: String, kind: String): State {
        val raw = dataStore.data.first()[key(kind)] ?: return State(server = server)
        val state = runCatching { json.decodeFromString(State.serializer(), raw) }.getOrNull()
        return if (state != null && state.server == server) state else State(server = server)
    }

    suspend fun set(server: String, kind: String, ids: Collection<Long>, lastFullScanAtMs: Long) {
        val state = State(
            server = server,
            ids = ids.filter { it > 0L }.distinct().sorted(),
            lastFullScanAtMs = lastFullScanAtMs,
        )
        val encoded = json.encodeToString(State.serializer(), state)
        dataStore.edit { prefs ->
            if (prefs[key(kind)] != encoded) prefs[key(kind)] = encoded
        }
    }

    /** Forgets everything (the account was wiped). */
    suspend fun clear() {
        dataStore.edit { it.clear() }
    }
}
