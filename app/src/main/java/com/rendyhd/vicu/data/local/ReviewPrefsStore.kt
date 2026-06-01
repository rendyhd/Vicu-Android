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

data class ReviewPrefs(
    val enabled: Boolean = true,
    val defaultCadenceDays: Int = 14,
    val excludeInbox: Boolean = true,
)

private val Context.reviewPrefsDataStore by preferencesDataStore(name = "review_prefs")

@Singleton
class ReviewPrefsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private companion object {
        val KEY_ENABLED = booleanPreferencesKey("review_enabled")
        val KEY_DEFAULT_CADENCE = intPreferencesKey("review_default_cadence_days")
        val KEY_EXCLUDE_INBOX = booleanPreferencesKey("review_exclude_inbox")
    }

    fun getPrefs(): Flow<ReviewPrefs> = context.reviewPrefsDataStore.data.map { p ->
        ReviewPrefs(
            enabled = p[KEY_ENABLED] ?: true,
            defaultCadenceDays = p[KEY_DEFAULT_CADENCE] ?: 14,
            excludeInbox = p[KEY_EXCLUDE_INBOX] ?: true,
        )
    }

    suspend fun setEnabled(v: Boolean) {
        context.reviewPrefsDataStore.edit { it[KEY_ENABLED] = v }
    }

    suspend fun setDefaultCadenceDays(v: Int) {
        context.reviewPrefsDataStore.edit { it[KEY_DEFAULT_CADENCE] = v.coerceIn(1, 365) }
    }

    suspend fun setExcludeInbox(v: Boolean) {
        context.reviewPrefsDataStore.edit { it[KEY_EXCLUDE_INBOX] = v }
    }
}
