package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.domain.model.SyncedPrefKeys
import com.rendyhd.vicu.util.PlannerHours
import com.rendyhd.vicu.util.TimeBlocks
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `planner.default_length` and `planner.hours` (contract 11.3): null is the default, on every device. */
class PlannerPreferencesTest {

    @Test
    fun withNothingStoredTheDefaultsHold() = runTest {
        val prefs = PlannerPreferences(InMemorySyncedEntries())
        assertEquals(TimeBlocks.DEFAULT_LENGTH_MINUTES, prefs.defaultLength.first())
        assertEquals(TimeBlocks.DEFAULT_HOURS, prefs.hours.first())
    }

    @Test
    fun whatAnotherDeviceStoredIsRead() = runTest {
        val prefs = PlannerPreferences(
            InMemorySyncedEntries(
                mapOf(
                    SyncedPrefKeys.PLANNER_DEFAULT_LENGTH to JsonPrimitive(45),
                    SyncedPrefKeys.PLANNER_HOURS to buildJsonArray {
                        add("07:30")
                        add("16:00")
                    },
                ),
            ),
        )
        assertEquals(45, prefs.defaultLength.first())
        assertEquals(PlannerHours("07:30", "16:00"), prefs.hours.first())
    }

    @Test
    fun aValueThisVersionCannotReadIsTheDefault() = runTest {
        val prefs = PlannerPreferences(
            InMemorySyncedEntries(
                mapOf(
                    SyncedPrefKeys.PLANNER_DEFAULT_LENGTH to JsonPrimitive("long"),
                    SyncedPrefKeys.PLANNER_HOURS to JsonPrimitive("mornings"),
                ),
            ),
        )
        assertEquals(TimeBlocks.DEFAULT_LENGTH_MINUTES, prefs.defaultLength.first())
        assertEquals(TimeBlocks.DEFAULT_HOURS, prefs.hours.first())
    }

    @Test
    fun theDefaultIsWrittenAsNullAndAnotherValueAsItself() = runTest {
        val synced = InMemorySyncedEntries()
        val prefs = PlannerPreferences(synced)

        assertTrue(prefs.setDefaultLength(60))
        assertEquals(JsonPrimitive(60), synced.entries.first()[SyncedPrefKeys.PLANNER_DEFAULT_LENGTH])
        assertTrue(prefs.setDefaultLength(TimeBlocks.DEFAULT_LENGTH_MINUTES))
        assertNull(synced.entries.first()[SyncedPrefKeys.PLANNER_DEFAULT_LENGTH])

        assertTrue(prefs.setHours(PlannerHours("08:00", "12:00")))
        assertEquals(PlannerHours("08:00", "12:00"), prefs.hours.first())
        assertTrue(prefs.setHours(TimeBlocks.DEFAULT_HOURS))
        assertNull(synced.entries.first()[SyncedPrefKeys.PLANNER_HOURS])
    }

    @Test
    fun anInvalidValueIsRefusedAndNothingIsWritten() = runTest {
        val synced = InMemorySyncedEntries()
        val prefs = PlannerPreferences(synced)

        assertFalse(prefs.setDefaultLength(37))
        assertFalse(prefs.setHours(PlannerHours("17:00", "09:00")))
        assertTrue(synced.entries.first().isEmpty())
    }
}
