package com.rendyhd.vicu.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class NotificationPrefs(
    val taskRemindersEnabled: Boolean = true,
    val dailySummaryEnabled: Boolean = false,
    val dailySummaryHour: Int = 8,
    val dailySummaryMinute: Int = 0,
    val soundEnabled: Boolean = true,
)

private val Context.notificationPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "notification_prefs")

@Singleton
class NotificationPrefsStore @Inject constructor(
    private val context: Context,
) {
    companion object {
        private val KEY_TASK_REMINDERS_ENABLED = booleanPreferencesKey("task_reminders_enabled")
        private val KEY_DAILY_SUMMARY_ENABLED = booleanPreferencesKey("daily_summary_enabled")
        private val KEY_DAILY_SUMMARY_HOUR = intPreferencesKey("daily_summary_hour")
        private val KEY_DAILY_SUMMARY_MINUTE = intPreferencesKey("daily_summary_minute")
        private val KEY_SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
    }

    fun getPrefs(): Flow<NotificationPrefs> =
        context.notificationPrefsDataStore.data.map { prefs ->
            NotificationPrefs(
                taskRemindersEnabled = prefs[KEY_TASK_REMINDERS_ENABLED] ?: true,
                dailySummaryEnabled = prefs[KEY_DAILY_SUMMARY_ENABLED] ?: false,
                dailySummaryHour = prefs[KEY_DAILY_SUMMARY_HOUR] ?: 8,
                dailySummaryMinute = prefs[KEY_DAILY_SUMMARY_MINUTE] ?: 0,
                soundEnabled = prefs[KEY_SOUND_ENABLED] ?: true,
            )
        }

    suspend fun setTaskRemindersEnabled(enabled: Boolean) {
        context.notificationPrefsDataStore.edit { it[KEY_TASK_REMINDERS_ENABLED] = enabled }
    }

    suspend fun setDailySummaryEnabled(enabled: Boolean) {
        context.notificationPrefsDataStore.edit { it[KEY_DAILY_SUMMARY_ENABLED] = enabled }
    }

    suspend fun setDailySummaryTime(hour: Int, minute: Int) {
        context.notificationPrefsDataStore.edit {
            it[KEY_DAILY_SUMMARY_HOUR] = hour
            it[KEY_DAILY_SUMMARY_MINUTE] = minute
        }
    }

    suspend fun setSoundEnabled(enabled: Boolean) {
        context.notificationPrefsDataStore.edit { it[KEY_SOUND_ENABLED] = enabled }
    }
}
