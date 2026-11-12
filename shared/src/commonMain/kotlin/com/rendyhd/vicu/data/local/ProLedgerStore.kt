package com.rendyhd.vicu.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The device's copy of the Pro ledger and its sync state (docs/cross-app-semantics-v1.md section
 * 21.7). The document is kept as the JSON it is, so fields a newer version wrote survive.
 */
@Serializable
data class ProLedgerLocalState(
    @SerialName("device_id") val deviceId: String,
    val document: JsonObject? = null,
    val dirty: Boolean = false,
    @SerialName("carrier_task_id") val carrierTaskId: Long? = null,
    @SerialName("last_synced_at") val lastSyncedAt: String? = null,
)

/**
 * Where [ProLedgerLocalState] lives, like [SyncedPrefsStore] for the synced preferences. It belongs
 * to the account: cleared by [LocalDataWiper.wipeEverything] and excluded from backups. Turning Pro
 * off leaves it as it is (contract 21.1, O2).
 */
class ProLedgerStore(private val dataStore: DataStore<Preferences>) {
    private companion object {
        val KEY_STATE = stringPreferencesKey("pro_ledger_v1")
        val json = Json { ignoreUnknownKeys = true }
    }

    private fun decode(raw: String?): ProLedgerLocalState? =
        raw?.let { runCatching { json.decodeFromString(ProLedgerLocalState.serializer(), it) }.getOrNull() }

    val state: Flow<ProLedgerLocalState?> = dataStore.data.map { decode(it[KEY_STATE]) }

    suspend fun load(): ProLedgerLocalState? = decode(dataStore.data.first()[KEY_STATE])

    suspend fun save(state: ProLedgerLocalState) {
        val encoded = json.encodeToString(ProLedgerLocalState.serializer(), state)
        dataStore.edit { if (it[KEY_STATE] != encoded) it[KEY_STATE] = encoded }
    }

    suspend fun clear() {
        dataStore.edit { it.clear() }
    }
}
