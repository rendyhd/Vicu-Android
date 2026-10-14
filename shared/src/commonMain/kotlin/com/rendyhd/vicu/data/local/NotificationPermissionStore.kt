package com.rendyhd.vicu.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.first

/** What this device remembers about asking for the notification permission. */
class NotificationPermissionStore(
    private val dataStore: DataStore<Preferences>,
) {
    private companion object {
        val KEY_REQUESTS_MADE = intPreferencesKey("requests_made")
        val KEY_AFTER_SETUP_HANDLED = booleanPreferencesKey("after_setup_handled")
    }

    suspend fun requestsMade(): Int = dataStore.data.first()[KEY_REQUESTS_MADE] ?: 0

    suspend fun afterSetupHandled(): Boolean = dataStore.data.first()[KEY_AFTER_SETUP_HANDLED] ?: false

    suspend fun recordRequest() {
        dataStore.edit { it[KEY_REQUESTS_MADE] = (it[KEY_REQUESTS_MADE] ?: 0) + 1 }
    }

    suspend fun markAfterSetupHandled() {
        dataStore.edit { it[KEY_AFTER_SETUP_HANDLED] = true }
    }
}
