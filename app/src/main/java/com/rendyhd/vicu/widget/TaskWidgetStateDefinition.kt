package com.rendyhd.vicu.widget

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.glance.state.GlanceStateDefinition
import kotlinx.serialization.json.Json
import java.io.File

object TaskWidgetStateDefinition : GlanceStateDefinition<Preferences> {

    val KEY_STATE = stringPreferencesKey("task_widget_state")

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getDataStore(
        context: Context,
        fileKey: String,
    ) = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create {
        context.preferencesDataStoreFile("widget_state_$fileKey")
    }

    override fun getLocation(context: Context, fileKey: String): File =
        context.preferencesDataStoreFile("widget_state_$fileKey")

    fun parseState(prefs: Preferences): TaskWidgetState {
        val raw = prefs[KEY_STATE] ?: return TaskWidgetState()
        return try {
            json.decodeFromString(TaskWidgetState.serializer(), raw)
        } catch (_: Exception) {
            TaskWidgetState()
        }
    }

    fun encodeState(state: TaskWidgetState): String =
        json.encodeToString(TaskWidgetState.serializer(), state)
}
