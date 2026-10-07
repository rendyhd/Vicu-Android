package com.rendyhd.vicu.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class WidgetPrefsStore(
    private val dataStore: DataStore<Preferences>,
) {
    companion object {
        private val KEY_SMART_ADD = booleanPreferencesKey("widget_smart_add")
        private val KEY_CONTEXT_NAV = booleanPreferencesKey("widget_context_nav")
    }

    val smartAdd: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_SMART_ADD] ?: true
    }

    val contextNav: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_CONTEXT_NAV] ?: true
    }

    suspend fun setSmartAdd(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_SMART_ADD] = enabled
        }
    }

    suspend fun setContextNav(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_CONTEXT_NAV] = enabled
        }
    }

    /** Back to the defaults. */
    suspend fun clear() {
        dataStore.edit { it.clear() }
    }
}

