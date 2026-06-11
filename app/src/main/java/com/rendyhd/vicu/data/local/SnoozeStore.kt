package com.rendyhd.vicu.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class SnoozeEntry(val taskId: Long, val title: String, val triggerAtMillis: Long)

private val Context.snoozeDataStore: DataStore<Preferences> by preferencesDataStore(name = "snooze_prefs")

/**
 * Persists pending snoozes so they survive reboots and are independent of the reminder
 * alarm sweep (which cancels and re-registers reminders on every sync).
 */
@Singleton
class SnoozeStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    companion object {
        private val KEY_SNOOZES = stringPreferencesKey("snoozes_json")
    }

    private val serializer = ListSerializer(SnoozeEntry.serializer())

    private fun decode(raw: String?): List<SnoozeEntry> =
        raw?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()

    suspend fun all(): List<SnoozeEntry> =
        decode(context.snoozeDataStore.data.first()[KEY_SNOOZES])

    suspend fun put(entry: SnoozeEntry) {
        context.snoozeDataStore.edit { prefs ->
            val updated = decode(prefs[KEY_SNOOZES]).filterNot { it.taskId == entry.taskId } + entry
            prefs[KEY_SNOOZES] = json.encodeToString(serializer, updated)
        }
    }

    suspend fun remove(taskId: Long) {
        context.snoozeDataStore.edit { prefs ->
            val updated = decode(prefs[KEY_SNOOZES]).filterNot { it.taskId == taskId }
            prefs[KEY_SNOOZES] = json.encodeToString(serializer, updated)
        }
    }
}
