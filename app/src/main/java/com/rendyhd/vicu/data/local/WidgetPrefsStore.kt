package com.rendyhd.vicu.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.widgetPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "widget_prefs")

@Singleton
class WidgetPrefsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private val KEY_SMART_ADD = booleanPreferencesKey("widget_smart_add")
        private val KEY_CONTEXT_NAV = booleanPreferencesKey("widget_context_nav")
    }

    val smartAdd: Flow<Boolean> = context.widgetPrefsDataStore.data.map { prefs ->
        prefs[KEY_SMART_ADD] ?: true
    }

    val contextNav: Flow<Boolean> = context.widgetPrefsDataStore.data.map { prefs ->
        prefs[KEY_CONTEXT_NAV] ?: true
    }

    suspend fun setSmartAdd(enabled: Boolean) {
        context.widgetPrefsDataStore.edit { prefs ->
            prefs[KEY_SMART_ADD] = enabled
        }
    }

    suspend fun setContextNav(enabled: Boolean) {
        context.widgetPrefsDataStore.edit { prefs ->
            prefs[KEY_CONTEXT_NAV] = enabled
        }
    }
}
