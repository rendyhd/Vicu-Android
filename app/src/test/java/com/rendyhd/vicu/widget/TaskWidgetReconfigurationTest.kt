package com.rendyhd.vicu.widget

import androidx.datastore.preferences.core.preferencesOf
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskWidgetReconfigurationTest {

    private val cachedState = TaskWidgetState(
        viewType = WidgetViewType.CUSTOM_LIST,
        viewId = "old-list",
        viewName = "Old list",
        tasks = listOf(WidgetTaskItem(id = 1L, title = "Old task")),
        totalCount = 12,
        lastUpdated = "2026-10-14T08:00:00Z",
        error = "Old view error",
        smartAdd = false,
        contextNav = false,
        addToProjectId = 42L,
    )

    @Test
    fun `changing lists clears old content and actions before the new query finishes`() {
        val views = listOf(
            WidgetViewType.TODAY to "",
            WidgetViewType.INBOX to "",
            WidgetViewType.UPCOMING to "",
            WidgetViewType.ANYTIME to "",
            WidgetViewType.PROJECT to "42",
            WidgetViewType.PROJECT to "43",
            WidgetViewType.CUSTOM_LIST to "42",
            WidgetViewType.CUSTOM_LIST to "new-list",
        )
        for ((oldType, oldId) in views) {
            for ((newType, newId) in views) {
                if (oldType == newType && oldId == newId) continue
                val old = cachedState.copy(viewType = oldType, viewId = oldId)
                val config = WidgetConfig(newType, newId, "New list", transparentBackground = true)

                val state = old.withConfig(config)

                assertEquals(newType, state.viewType)
                assertEquals(newId, state.viewId)
                assertEquals("New list", state.viewName)
                assertTrue(state.transparentBackground)
                assertTrue(state.tasks.isEmpty())
                assertEquals(0, state.totalCount)
                assertEquals("", state.lastUpdated)
                assertNull(state.error)
                assertEquals(0L, state.addToProjectId)
                assertFalse(state.smartAdd)
                assertFalse(state.contextNav)
            }
        }
    }

    @Test
    fun `transparency-only edits retain cached rows counts errors and actions`() {
        for (transparent in listOf(false, true)) {
            val old = cachedState.copy(transparentBackground = !transparent)
            val config = WidgetConfig(old.viewType, old.viewId, old.viewName, transparent)

            assertEquals(old.copy(transparentBackground = transparent), old.withConfig(config))
        }
    }

    @Test
    fun `renaming the same view keeps its cached content`() {
        val config = WidgetConfig(cachedState.viewType, cachedState.viewId, "Renamed list")

        assertEquals(cachedState.copy(viewName = "Renamed list"), cachedState.withConfig(config))
    }

    @Test
    fun `loading state and transparency survive the Glance state round trip`() {
        val state = cachedState.withConfig(
            WidgetConfig(WidgetViewType.PROJECT, "43", "New project", transparentBackground = true),
        )
        val prefs = preferencesOf(
            TaskWidgetStateDefinition.KEY_STATE to TaskWidgetStateDefinition.encodeState(state),
        )

        assertEquals(state, TaskWidgetStateDefinition.parseState(prefs))
    }

    @Test
    fun `existing saved configs and widget states default to a colored background`() {
        val config = Json.decodeFromString<WidgetConfig>(
            """{"viewType":"PROJECT","viewId":"42","viewName":"Project"}""",
        )
        val prefs = preferencesOf(
            TaskWidgetStateDefinition.KEY_STATE to
                """{"viewType":"PROJECT","viewId":"42","viewName":"Project","tasks":[{"id":1,"title":"Task"}],"totalCount":1,"lastUpdated":"2026-10-14T08:00:00Z"}""",
        )
        val state = TaskWidgetStateDefinition.parseState(prefs)

        assertFalse(config.transparentBackground)
        assertFalse(state.transparentBackground)
        assertEquals(listOf(WidgetTaskItem(id = 1L, title = "Task")), state.tasks)
        assertEquals(state, state.withConfig(config))
    }
}
