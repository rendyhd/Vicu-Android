package com.rendyhd.vicu.data.local

import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.domain.model.TaskOrder
import com.rendyhd.vicu.domain.model.TaskOrderSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TaskOrderPrefsStoreTest {

    @Test
    fun `nothing stored is the default order with no overrides`() = runTest {
        val store = TaskOrderPrefsStore(InMemoryPreferencesDataStore())
        assertEquals(TaskOrderSettings(), store.settings.first())
    }

    @Test
    fun `the global order and project overrides are kept and removed`() = runTest {
        var changes = 0
        val store = TaskOrderPrefsStore(InMemoryPreferencesDataStore(), onChange = { changes++ })

        store.setGlobalOrder(TaskOrder.PRIORITY)
        store.setProjectOrder(7L, TaskOrder.MANUAL)
        store.setProjectOrder(12L, TaskOrder.TITLE)
        assertEquals(
            TaskOrderSettings(TaskOrder.PRIORITY, mapOf(7L to TaskOrder.MANUAL, 12L to TaskOrder.TITLE)),
            store.settings.first(),
        )

        store.setProjectOrder(7L, null)
        assertEquals(mapOf(12L to TaskOrder.TITLE), store.settings.first().projectOverrides)

        store.clearProjectOverrides()
        assertEquals(TaskOrderSettings(TaskOrder.PRIORITY), store.settings.first())
        assertEquals(5, changes)
    }

    @Test
    fun `clearing for another account keeps the global order`() = runTest {
        val store = TaskOrderPrefsStore(InMemoryPreferencesDataStore())
        store.setGlobalOrder(TaskOrder.NEWEST)
        store.setProjectOrder(3L, TaskOrder.OLDEST)

        store.clear()

        assertEquals(TaskOrderSettings(TaskOrder.NEWEST), store.settings.first())
    }
}
