package com.rendyhd.vicu.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode {
    System,
    Light,
    Dark,
}

private val Context.themePrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "theme_prefs")

@Singleton
class ThemePrefsStore @Inject constructor(
    private val context: Context,
) {
    companion object {
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
    }

    val themeMode: Flow<ThemeMode> = context.themePrefsDataStore.data.map { prefs ->
        when (prefs[KEY_THEME_MODE]) {
            "light" -> ThemeMode.Light
            "dark" -> ThemeMode.Dark
            else -> ThemeMode.System
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.themePrefsDataStore.edit { prefs ->
            prefs[KEY_THEME_MODE] = when (mode) {
                ThemeMode.System -> "system"
                ThemeMode.Light -> "light"
                ThemeMode.Dark -> "dark"
            }
        }
    }
}
