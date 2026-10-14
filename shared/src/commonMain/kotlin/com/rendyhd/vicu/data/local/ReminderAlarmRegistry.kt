package com.rendyhd.vicu.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Persists which reminder alarms are currently registered with the system, as task id to
 * PendingIntent request codes. AlarmManager cannot list alarms, so without this the only way to
 * cancel an alarm is to probe request codes one by one, and an alarm for a task that was
 * completed or deleted elsewhere is never found. With the registry every registered alarm can be
 * cancelled exactly, with one lookup per alarm.
 *
 * Alarms do not survive a restore onto another device, so the backing file is excluded from
 * backups.
 */
class ReminderAlarmRegistry(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
) {
    private companion object {
        val KEY_REGISTRY = stringPreferencesKey("reminder_alarm_registry")
        val SERIALIZER = MapSerializer(String.serializer(), ListSerializer(Int.serializer()))
    }

    private fun decode(raw: String?): Map<Long, Set<Int>> {
        val decoded = raw?.let { runCatching { json.decodeFromString(SERIALIZER, it) }.getOrNull() }
            ?: return emptyMap()
        return decoded.mapNotNull { (key, codes) ->
            key.toLongOrNull()?.let { it to codes.toSet() }
        }.toMap()
    }

    suspend fun all(): Map<Long, Set<Int>> = decode(dataStore.data.first()[KEY_REGISTRY])

    /** Replaces the whole registry. Tasks without codes are not stored. */
    suspend fun replaceAll(registry: Map<Long, Set<Int>>) {
        dataStore.edit { prefs ->
            val cleaned = registry.filterValues { it.isNotEmpty() }
            if (cleaned.isEmpty()) {
                prefs.remove(KEY_REGISTRY)
            } else {
                prefs[KEY_REGISTRY] = json.encodeToString(
                    SERIALIZER,
                    cleaned.entries.associate { (taskId, codes) -> taskId.toString() to codes.sorted() },
                )
            }
        }
    }

    suspend fun clear() = replaceAll(emptyMap())
}
