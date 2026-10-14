package com.rendyhd.vicu.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.rendyhd.vicu.domain.model.BottomBarConfig
import com.rendyhd.vicu.domain.model.BottomBarSlot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class BottomBarPrefsStore(
    private val dataStore: DataStore<Preferences>,
) {
    companion object {
        private val KEY_SLOTS = stringPreferencesKey("bottom_bar_slots_json")
        private val KEY_SMART_LIST_COLORS = booleanPreferencesKey("bottom_bar_smart_list_colors")
        private val json = Json { ignoreUnknownKeys = true }

        private fun decodeSlots(raw: String?): List<BottomBarSlot> {
            raw ?: return BottomBarSlot.DEFAULT_SLOTS
            return try {
                val decoded = json.decodeFromString(ListSerializer(BottomBarSlot.serializer()), raw)
                if (decoded.size == 3) decoded else BottomBarSlot.DEFAULT_SLOTS
            } catch (_: Exception) {
                BottomBarSlot.DEFAULT_SLOTS
            }
        }
    }

    val config: Flow<BottomBarConfig> =
        dataStore.data.map { prefs ->
            BottomBarConfig(
                slots = decodeSlots(prefs[KEY_SLOTS]),
                smartListColors = prefs[KEY_SMART_LIST_COLORS] ?: true,
            )
        }

    val slots: Flow<List<BottomBarSlot>> = config.map { it.slots }

    suspend fun saveSlots(slots: List<BottomBarSlot>) {
        require(slots.size == 3) { "Bottom bar must have exactly 3 configurable slots" }
        dataStore.edit { prefs ->
            prefs[KEY_SLOTS] = json.encodeToString(ListSerializer(BottomBarSlot.serializer()), slots)
        }
    }

    suspend fun setSmartListColors(enabled: Boolean) {
        dataStore.edit { it[KEY_SMART_LIST_COLORS] = enabled }
    }

    /** The slots and the colours as they are out of the box. */
    suspend fun reset() {
        dataStore.edit { prefs ->
            prefs[KEY_SLOTS] = json.encodeToString(ListSerializer(BottomBarSlot.serializer()), BottomBarSlot.DEFAULT_SLOTS)
            prefs.remove(KEY_SMART_LIST_COLORS)
        }
    }

    suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    suspend fun updateSlot(index: Int, slot: BottomBarSlot) {
        require(index in 0..2) { "Slot index must be 0, 1, or 2" }
        dataStore.edit { prefs ->
            val current = prefs[KEY_SLOTS]?.let {
                try {
                    json.decodeFromString(ListSerializer(BottomBarSlot.serializer()), it).toMutableList()
                } catch (_: Exception) {
                    BottomBarSlot.DEFAULT_SLOTS.toMutableList()
                }
            } ?: BottomBarSlot.DEFAULT_SLOTS.toMutableList()

            current[index] = slot
            prefs[KEY_SLOTS] = json.encodeToString(ListSerializer(BottomBarSlot.serializer()), current)
        }
    }
}
