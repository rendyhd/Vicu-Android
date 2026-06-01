package com.rendyhd.vicu.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Logbook retention preferences. When [LogbookPrefs.enabled], completed tasks finished more
 * than [LogbookPrefs.retentionDays] days ago are hidden from the Logbook view (a non-destructive
 * display filter — nothing is deleted from the server). Off by default. See issue #6.
 */
data class LogbookPrefs(
    val enabled: Boolean = false,
    val retentionDays: Int = 90,
)

private val Context.logbookPrefsDataStore by preferencesDataStore(name = "logbook_prefs")

@Singleton
class LogbookPrefsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private companion object {
        val KEY_ENABLED = booleanPreferencesKey("logbook_retention_enabled")
        val KEY_RETENTION_DAYS = intPreferencesKey("logbook_retention_days")
    }

    fun getPrefs(): Flow<LogbookPrefs> = context.logbookPrefsDataStore.data.map { p ->
        LogbookPrefs(
            enabled = p[KEY_ENABLED] ?: false,
            retentionDays = p[KEY_RETENTION_DAYS] ?: 90,
        )
    }

    suspend fun setEnabled(v: Boolean) {
        context.logbookPrefsDataStore.edit { it[KEY_ENABLED] = v }
    }

    suspend fun setRetentionDays(v: Int) {
        context.logbookPrefsDataStore.edit { it[KEY_RETENTION_DAYS] = v.coerceIn(1, 3650) }
    }
}
