package com.rendyhd.vicu.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rendyhd.vicu.util.parser.ParserConfig
import com.rendyhd.vicu.util.parser.SyntaxMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.nlpPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "nlp_prefs")

@Singleton
class NlpPrefsStore @Inject constructor(
    private val context: Context,
) {
    companion object {
        private val KEY_NLP_ENABLED = booleanPreferencesKey("nlp_enabled")
        private val KEY_SYNTAX_MODE = stringPreferencesKey("nlp_syntax_mode")
        private val KEY_BANG_TODAY = booleanPreferencesKey("bang_today")
    }

    val config: Flow<ParserConfig> = context.nlpPrefsDataStore.data.map { prefs ->
        ParserConfig(
            enabled = prefs[KEY_NLP_ENABLED] ?: true,
            syntaxMode = when (prefs[KEY_SYNTAX_MODE]) {
                "vikunja" -> SyntaxMode.VIKUNJA
                else -> SyntaxMode.TODOIST
            },
            bangToday = prefs[KEY_BANG_TODAY] ?: true,
        )
    }

    suspend fun setEnabled(enabled: Boolean) {
        context.nlpPrefsDataStore.edit { it[KEY_NLP_ENABLED] = enabled }
    }

    suspend fun setSyntaxMode(mode: SyntaxMode) {
        context.nlpPrefsDataStore.edit {
            it[KEY_SYNTAX_MODE] = when (mode) {
                SyntaxMode.TODOIST -> "todoist"
                SyntaxMode.VIKUNJA -> "vikunja"
            }
        }
    }

    suspend fun setBangToday(enabled: Boolean) {
        context.nlpPrefsDataStore.edit { it[KEY_BANG_TODAY] = enabled }
    }
}
