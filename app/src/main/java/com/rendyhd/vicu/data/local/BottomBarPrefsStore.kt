package com.rendyhd.vicu.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rendyhd.vicu.domain.model.BottomBarSlot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.bottomBarPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "bottom_bar_prefs")

@Singleton
class BottomBarPrefsStore @Inject constructor(
    private val context: Context,
) {
    companion object {
        private val KEY_SLOTS = stringPreferencesKey("bottom_bar_slots_json")
        private val json = Json { ignoreUnknownKeys = true }
    }

    val slots: Flow<List<BottomBarSlot>> =
        context.bottomBarPrefsDataStore.data.map { prefs ->
            val raw = prefs[KEY_SLOTS] ?: return@map BottomBarSlot.DEFAULT_SLOTS
            try {
                val decoded = json.decodeFromString(ListSerializer(BottomBarSlot.serializer()), raw)
                if (decoded.size == 3) decoded else BottomBarSlot.DEFAULT_SLOTS
            } catch (_: Exception) {
                BottomBarSlot.DEFAULT_SLOTS
            }
        }

    suspend fun saveSlots(slots: List<BottomBarSlot>) {
        require(slots.size == 3) { "Bottom bar must have exactly 3 configurable slots" }
        context.bottomBarPrefsDataStore.edit { prefs ->
            prefs[KEY_SLOTS] = json.encodeToString(ListSerializer(BottomBarSlot.serializer()), slots)
        }
    }

    suspend fun clear() {
        context.bottomBarPrefsDataStore.edit { it.clear() }
    }

    suspend fun updateSlot(index: Int, slot: BottomBarSlot) {
        require(index in 0..2) { "Slot index must be 0, 1, or 2" }
        context.bottomBarPrefsDataStore.edit { prefs ->
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
