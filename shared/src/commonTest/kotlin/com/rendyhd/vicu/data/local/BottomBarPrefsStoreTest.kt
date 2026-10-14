package com.rendyhd.vicu.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.domain.model.BottomBarConfig
import com.rendyhd.vicu.domain.model.BottomBarSlot
import com.rendyhd.vicu.domain.model.BottomBarSlotType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BottomBarPrefsStoreTest {

    @Test
    fun `out of the box the bar has the default slots and coloured smart lists`() = runTest {
        val store = BottomBarPrefsStore(InMemoryPreferencesDataStore())
        assertEquals(BottomBarConfig(), store.config.first())
        assertTrue(store.config.first().smartListColors)
    }

    @Test
    fun `the smart list colours can be turned off and on`() = runTest {
        val store = BottomBarPrefsStore(InMemoryPreferencesDataStore())
        store.setSmartListColors(false)
        assertFalse(store.config.first().smartListColors)
        store.setSmartListColors(true)
        assertTrue(store.config.first().smartListColors)
    }

    @Test
    fun `a slot keeps the colour chosen for it`() = runTest {
        val store = BottomBarPrefsStore(InMemoryPreferencesDataStore())
        val project = BottomBarSlot(BottomBarSlotType.PROJECT, referenceId = "7", iconKey = "work", colorHex = "#4186e0")
        store.updateSlot(2, project)
        assertEquals(project, store.slots.first()[2])
    }

    @Test
    fun `slots saved before colours existed read as having none`() = runTest {
        val data = InMemoryPreferencesDataStore()
        data.edit {
            it[stringPreferencesKey("bottom_bar_slots_json")] =
                """[{"type":"TODAY"},{"type":"PROJECT","referenceId":"7","iconKey":"work"},{"type":"ANYTIME"}]"""
        }
        val slots = BottomBarPrefsStore(data).slots.first()
        assertEquals(BottomBarSlot(BottomBarSlotType.PROJECT, referenceId = "7", iconKey = "work"), slots[1])
        assertEquals("", slots[1].colorHex)
    }

    @Test
    fun `reset puts back the default slots and the smart list colours`() = runTest {
        val store = BottomBarPrefsStore(InMemoryPreferencesDataStore())
        store.updateSlot(0, BottomBarSlot(BottomBarSlotType.CUSTOM_LIST, referenceId = "l1", colorHex = "#e8384f"))
        store.setSmartListColors(false)

        store.reset()

        assertEquals(BottomBarConfig(), store.config.first())
    }
}
