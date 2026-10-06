package com.rendyhd.vicu.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.rendyhd.vicu.util.randomUuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class RoutinePrefsStore(
    private val dataStore: DataStore<Preferences>,
) {
    companion object {
        private val KEY_DEVICE_ID = stringPreferencesKey("routine_device_id")
        private val KEY_REMINDERS_ENABLED = booleanPreferencesKey("routine_reminders_enabled")
    }

    val remindersEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_REMINDERS_ENABLED] ?: true }

    suspend fun getOrCreateDeviceId(): String {
        dataStore.data.first()[KEY_DEVICE_ID]?.takeIf { it.isNotBlank() }?.let { return it }
        val generated = randomUuid()
        dataStore.edit { prefs ->
            if (prefs[KEY_DEVICE_ID].isNullOrBlank()) prefs[KEY_DEVICE_ID] = generated
        }
        return dataStore.data.first()[KEY_DEVICE_ID] ?: generated
    }

    suspend fun setRemindersEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_REMINDERS_ENABLED] = enabled }
    }

    /** Back to defaults, with a new device id for the next account's routine history. */
    suspend fun clear() {
        dataStore.edit { it.clear() }
    }
}
