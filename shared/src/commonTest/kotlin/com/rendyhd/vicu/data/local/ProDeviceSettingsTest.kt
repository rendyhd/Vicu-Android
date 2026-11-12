package com.rendyhd.vicu.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.util.pro.ProFocus
import com.rendyhd.vicu.util.pro.ProLayer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pro's device settings (cross-app semantics 21.1): off by default, never synced. */
class ProDeviceSettingsTest {

    @Test
    fun `out of the box Pro is off and every layer would be on`() = runTest {
        val prefs = BehaviorPrefsStore(InMemoryPreferencesDataStore()).getPrefs().first()

        assertFalse(prefs.proEnabled)
        assertFalse(prefs.isProEnabled())
        assertFalse(prefs.isProEnabled(ProLayer.PROMISES))
        assertTrue(ProLayer.entries.all { prefs.proLayers.isOn(it) })
        assertTrue(prefs.proListViews.isEmpty())
        assertNull(prefs.proFocus)
        assertFalse(prefs.proAssistant)
        assertFalse(prefs.proOnboardingSeen)
    }

    @Test
    fun `the switch and a layer`() = runTest {
        val store = BehaviorPrefsStore(InMemoryPreferencesDataStore())

        store.setProEnabled(true)
        store.setProLayer(ProLayer.PORTFOLIO, on = false)

        val prefs = store.getPrefs().first()
        assertTrue(prefs.isProEnabled())
        assertTrue(prefs.isProEnabled(ProLayer.AVAILABILITY))
        assertFalse(prefs.isProEnabled(ProLayer.PORTFOLIO))

        store.setProEnabled(false)
        assertFalse(store.getPrefs().first().isProEnabled(ProLayer.AVAILABILITY))
    }

    @Test
    fun `a layer a newer version stored is kept when another changes`() = runTest {
        val data = InMemoryPreferencesDataStore()
        data.edit { it[stringPreferencesKey("pro_layers")] = """{"meetings":false,"power":false}""" }
        val store = BehaviorPrefsStore(data)

        store.setProLayer(ProLayer.POWER, on = true)

        assertEquals("""{"meetings":false,"power":true}""", data.data.first()[stringPreferencesKey("pro_layers")])
        assertTrue(store.getPrefs().first().proLayers.power)
    }

    @Test
    fun `list views, focus, the assistant, snoozes and the notice`() = runTest {
        val data = InMemoryPreferencesDataStore()
        val store = BehaviorPrefsStore(data)

        store.setProListView("anytime", ProListView.REMAINING)
        store.setProListView("project:12", ProListView.ALL)
        store.setProFocus(ProFocus(listOf("c1"), listOf(4L)))
        store.setProAssistant(true)
        store.setVerdictSnooze("42", VerdictSnooze(due = "2026-11-12", until = "2026-11-13"))
        store.setProOnboardingSeen(true)

        val prefs = store.getPrefs().first()
        assertEquals(mapOf("anytime" to ProListView.REMAINING, "project:12" to ProListView.ALL), prefs.proListViews)
        assertEquals(ProFocus(listOf("c1"), listOf(4L)), prefs.proFocus)
        assertTrue(prefs.proAssistant)
        assertEquals(mapOf("42" to VerdictSnooze("2026-11-12", "2026-11-13")), prefs.proVerdictSnooze)
        assertTrue(prefs.proOnboardingSeen)

        store.setProListView("anytime", null)
        store.setProListView("project:12", null)
        store.setProFocus(null)
        store.setVerdictSnooze("42", null)
        val cleared = store.getPrefs().first()
        assertTrue(cleared.proListViews.isEmpty())
        assertNull(cleared.proFocus)
        assertTrue(cleared.proVerdictSnooze.isEmpty())
        assertNull(data.data.first()[stringPreferencesKey("pro_list_views")])
    }

    @Test
    fun `values that do not read are left out`() = runTest {
        val data = InMemoryPreferencesDataStore()
        data.edit {
            it[stringPreferencesKey("pro_list_views")] = """{"anytime":"columns","tag:4":"available"}"""
            it[stringPreferencesKey("pro_verdict_snooze")] = """{"1":{"due":"2026-11-12"},"2":"x"}"""
            it[stringPreferencesKey("pro_focus")] = "not json"
        }

        val prefs = BehaviorPrefsStore(data).getPrefs().first()

        assertEquals(mapOf("tag:4" to ProListView.AVAILABLE), prefs.proListViews)
        assertTrue(prefs.proVerdictSnooze.isEmpty())
        assertNull(prefs.proFocus)
    }
}
