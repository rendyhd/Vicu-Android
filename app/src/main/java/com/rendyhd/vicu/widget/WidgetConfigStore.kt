package com.rendyhd.vicu.widget

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

private val Context.widgetConfigDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "widget_configs")

object WidgetConfigStore {

    private val json = Json { ignoreUnknownKeys = true }

    private fun configKey(appWidgetId: Int) =
        stringPreferencesKey("config_$appWidgetId")

    suspend fun saveConfig(context: Context, appWidgetId: Int, config: WidgetConfig) {
        context.widgetConfigDataStore.edit { prefs ->
            prefs[configKey(appWidgetId)] = json.encodeToString(WidgetConfig.serializer(), config)
        }
    }

    suspend fun getConfig(context: Context, appWidgetId: Int): WidgetConfig? {
        val prefs = context.widgetConfigDataStore.data.first()
        val raw = prefs[configKey(appWidgetId)] ?: return null
        return try {
            json.decodeFromString(WidgetConfig.serializer(), raw)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun deleteConfig(context: Context, appWidgetId: Int) {
        context.widgetConfigDataStore.edit { prefs ->
            prefs.remove(configKey(appWidgetId))
        }
    }
}
