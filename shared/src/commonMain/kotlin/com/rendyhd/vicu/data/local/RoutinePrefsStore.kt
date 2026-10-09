package com.rendyhd.vicu.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.rendyhd.vicu.util.randomUuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Where routines show on this device. Both are on unless the user turned them off. */
data class RoutineVisibility(
    /** The routines feature: drawer entry, Today section and reminders. */
    val enabled: Boolean = true,
    /** Whether Today lists the routines still open today (only while [enabled]). */
    val showInToday: Boolean = true,
) {
    val inToday: Boolean get() = enabled && showInToday
}

class RoutinePrefsStore(
    private val dataStore: DataStore<Preferences>,
) {
    companion object {
        private val KEY_DEVICE_ID = stringPreferencesKey("routine_device_id")
        private val KEY_REMINDERS_ENABLED = booleanPreferencesKey("routine_reminders_enabled")
        private val KEY_ENABLED = booleanPreferencesKey("routines_enabled")
        private val KEY_SHOW_IN_TODAY = booleanPreferencesKey("routines_show_in_today")
    }

    val remindersEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_REMINDERS_ENABLED] ?: true }

    val visibility: Flow<RoutineVisibility> = dataStore.data.map {
        RoutineVisibility(
            enabled = it[KEY_ENABLED] ?: true,
            showInToday = it[KEY_SHOW_IN_TODAY] ?: true,
        )
    }.distinctUntilChanged()

    val enabled: Flow<Boolean> = visibility.map { it.enabled }.distinctUntilChanged()

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

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_ENABLED] = enabled }
    }

    suspend fun setShowInToday(show: Boolean) {
        dataStore.edit { it[KEY_SHOW_IN_TODAY] = show }
    }

    /**
     * Back to defaults, with a new device id for the next account's routine history. Whether
     * routines show at all is a preference of this device, like the review settings, and is kept.
     */
    suspend fun clear() {
        dataStore.edit { prefs ->
            val enabled = prefs[KEY_ENABLED]
            val showInToday = prefs[KEY_SHOW_IN_TODAY]
            prefs.clear()
            enabled?.let { prefs[KEY_ENABLED] = it }
            showInToday?.let { prefs[KEY_SHOW_IN_TODAY] = it }
        }
    }
}
