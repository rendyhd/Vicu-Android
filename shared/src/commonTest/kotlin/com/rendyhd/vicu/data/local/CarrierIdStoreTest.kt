package com.rendyhd.vicu.data.local

import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CarrierIdStoreTest {

    private val server = "https://vikunja.example"

    @Test
    fun `nothing stored reads as an empty state`() = runTest {
        val state = CarrierIdStore(InMemoryPreferencesDataStore()).get(server, "custom-lists")

        assertEquals(emptyList(), state.ids)
        assertEquals(0L, state.lastFullScanAtMs)
    }

    @Test
    fun `ids are stored sorted and without duplicates or temp ids`() = runTest {
        val store = CarrierIdStore(InMemoryPreferencesDataStore())

        store.set(server, "custom-lists", listOf(902L, 900L, 902L, -5L, 0L), 77L)

        val state = store.get(server, "custom-lists")
        assertEquals(listOf(900L, 902L), state.ids)
        assertEquals(77L, state.lastFullScanAtMs)
    }

    @Test
    fun `the state belongs to one server`() = runTest {
        val store = CarrierIdStore(InMemoryPreferencesDataStore())
        store.set(server, "custom-lists", listOf(900L), 77L)

        val other = store.get("https://other.example", "custom-lists")

        assertEquals(emptyList(), other.ids)
        assertEquals(0L, other.lastFullScanAtMs)
        assertEquals(listOf(900L), store.get(server, "custom-lists").ids)
    }

    @Test
    fun `kinds are kept apart`() = runTest {
        val store = CarrierIdStore(InMemoryPreferencesDataStore())
        store.set(server, "custom-lists", listOf(900L), 1L)
        store.set(server, "routine", listOf(700L), 2L)

        assertEquals(listOf(900L), store.get(server, "custom-lists").ids)
        assertEquals(listOf(700L), store.get(server, "routine").ids)
    }

    @Test
    fun `clearing forgets everything`() = runTest {
        val store = CarrierIdStore(InMemoryPreferencesDataStore())
        store.set(server, "custom-lists", listOf(900L), 1L)

        store.clear()

        assertEquals(emptyList(), store.get(server, "custom-lists").ids)
    }
}
