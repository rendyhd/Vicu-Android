package com.rendyhd.vicu.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode {
    System,
    Light,
    Dark,
}

class ThemePrefsStore(
    private val dataStore: DataStore<Preferences>,
) {
    companion object {
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_USE_DEVICE_COLORS = booleanPreferencesKey("use_device_colors")
    }

    val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        when (prefs[KEY_THEME_MODE]) {
            "light" -> ThemeMode.Light
            "dark" -> ThemeMode.Dark
            else -> ThemeMode.System
        }
    }

    /** Material You: use the wallpaper-derived dynamic colour scheme (default off: the Vicu scheme). */
    val useDeviceColors: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_USE_DEVICE_COLORS] ?: false
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { prefs ->
            prefs[KEY_THEME_MODE] = when (mode) {
                ThemeMode.System -> "system"
                ThemeMode.Light -> "light"
                ThemeMode.Dark -> "dark"
            }
        }
    }

    suspend fun setUseDeviceColors(enabled: Boolean) {
        dataStore.edit { it[KEY_USE_DEVICE_COLORS] = enabled }
    }
}

