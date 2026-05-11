package com.rendyhd.vicu.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide behavior preferences that don't fit elsewhere:
 *  - Whether to play a sound on task completion (+ optional custom sound file URI)
 *  - Whether to require confirmation before destructive deletes
 */
data class BehaviorPrefs(
    val completionSoundEnabled: Boolean = true,
    val completionSoundUri: String? = null,
    val confirmBeforeDelete: Boolean = true,
)

private val Context.behaviorPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "behavior_prefs")

@Singleton
class BehaviorPrefsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private val KEY_COMPLETION_SOUND_ENABLED = booleanPreferencesKey("completion_sound_enabled")
        private val KEY_COMPLETION_SOUND_URI = stringPreferencesKey("completion_sound_uri")
        private val KEY_CONFIRM_BEFORE_DELETE = booleanPreferencesKey("confirm_before_delete")
    }

    fun getPrefs(): Flow<BehaviorPrefs> =
        context.behaviorPrefsDataStore.data.map { prefs ->
            BehaviorPrefs(
                completionSoundEnabled = prefs[KEY_COMPLETION_SOUND_ENABLED] ?: true,
                completionSoundUri = prefs[KEY_COMPLETION_SOUND_URI]?.takeIf { it.isNotBlank() },
                confirmBeforeDelete = prefs[KEY_CONFIRM_BEFORE_DELETE] ?: true,
            )
        }

    suspend fun setCompletionSoundEnabled(enabled: Boolean) {
        context.behaviorPrefsDataStore.edit { it[KEY_COMPLETION_SOUND_ENABLED] = enabled }
    }

    suspend fun setCompletionSoundUri(uri: String?) {
        context.behaviorPrefsDataStore.edit {
            if (uri.isNullOrBlank()) it.remove(KEY_COMPLETION_SOUND_URI)
            else it[KEY_COMPLETION_SOUND_URI] = uri
        }
    }

    suspend fun setConfirmBeforeDelete(enabled: Boolean) {
        context.behaviorPrefsDataStore.edit { it[KEY_CONFIRM_BEFORE_DELETE] = enabled }
    }
}
