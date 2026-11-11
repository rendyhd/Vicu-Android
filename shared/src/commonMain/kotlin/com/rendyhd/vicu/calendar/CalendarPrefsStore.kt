package com.rendyhd.vicu.calendar

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** One calendar's choices on this device (contract 20.9); every default is the contract's. */
@Serializable
data class CalendarChoice(
    val shown: Boolean = true,
    /** `#rrggbb` chosen for it, or null for the calendar's own colour (or the palette's). */
    val color: String? = null,
    val countsAsBusy: Boolean = true,
    /** Show only "Busy", not titles (20.8). */
    val busyOnly: Boolean = false,
)

/**
 * The device's calendar choices: shown, colour, counts as busy and Busy only per phone calendar.
 * A setting of the device, never synced (calendar ids differ per device), and no event is stored
 * here. DataStore file `calendar_prefs`.
 */
class CalendarPrefsStore(private val dataStore: DataStore<Preferences>) {
    private val json = Json { ignoreUnknownKeys = true }

    val choices: Flow<Map<String, CalendarChoice>> = dataStore.data
        .map { prefs -> decode(prefs[KEY_CHOICES]) }
        .distinctUntilChanged()

    suspend fun update(calendarId: String, change: (CalendarChoice) -> CalendarChoice) {
        dataStore.edit { prefs ->
            val all = decode(prefs[KEY_CHOICES])
            val next = change(all[calendarId] ?: CalendarChoice())
            val written = if (next == CalendarChoice()) all - calendarId else all + (calendarId to next)
            prefs[KEY_CHOICES] = json.encodeToString(MAP, written)
        }
    }

    suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    private fun decode(text: String?): Map<String, CalendarChoice> =
        text?.let { runCatching { json.decodeFromString(MAP, it) }.getOrNull() }.orEmpty()

    private companion object {
        val KEY_CHOICES = stringPreferencesKey("calendar_choices")
        val MAP = MapSerializer(String.serializer(), CalendarChoice.serializer())
    }
}
